package com.kadir.linkdnm

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.text.InputType
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi

/** Embedded section in the one and only Activity. */
@UnstableApi
class VideoPanel(private val host: Activity, private val storage: Mp3Storage) {
    companion object { const val IMAGE_REQUEST = 5101; const val SOUND_REQUEST = 5102 }
    private val emerald = Color.rgb(9, 106, 82)
    private val pale = Color.rgb(222, 242, 232)
    private val dark = Color.rgb(20, 57, 45)
    private val gray = Color.rgb(95, 120, 110)
    private val runner = VideoMaker(host)
    private val handler = Handler(Looper.getMainLooper())
    private val container = column(16)
    private val imageView = ImageView(host)
    private val imageStatus = text("Fotoğraf henüz seçilmedi.")
    private val soundStatus = text("Müzik henüz seçilmedi.")
    private val introStatus = text("Başlangıç: 0 saniye")
    private val songList = column(0)
    private val seek = SeekBar(host)
    private val state = text("Video hazırlamak için fotoğraf seç.")
    private val progress = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal)
    private val make = button("🎬 VİDEO OLUŞTUR", true)
    private val durationInput = EditText(host).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        setText("1")
        textSize = 20f
        hint = "Video süresi (dakika, 1-4)"
        contentDescription = "Video süresi dakika"
    }
    private val abort = button("İptal Et")
    private val listen = button("▶ Seçilen başlangıcı dinle")
    private val suggestionStatus = text("Öneri aranıyor...", 14f)
    private val suggestListen = button("▶ Tahmini dinle")
    private val suggestApply = button("✓ Tahmini kullan")
    private val open = button("▶ Oluşturulan videoyu aç")
    private var minute = 1
    private var minuteButtons = mapOf<Int,Button>()
    private var image: Uri? = null
    private var sound: Uri? = null
    private var soundLength = 0L
    private var startAt = 0L
    private var suggestedStart: Long? = null
    private var detectionJob = 0
    private var player: MediaPlayer? = null
    private var videoUri: Uri? = null
    private var token = 0
    private val poll = object: Runnable {
        override fun run() {
            if (runner.busy) {
                progress.progress = runner.progress()
                state.text = "Video hazırlanıyor: %" + progress.progress
                handler.postDelayed(this, 600)
            }
        }
    }
    private fun dp(n:Int) = (n*host.resources.displayMetrics.density + .5f).toInt()
    private fun rounded(c:Int) = GradientDrawable().apply { setColor(c); cornerRadius=dp(16).toFloat() }
    private fun column(p:Int) = LinearLayout(host).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(p),dp(p),dp(p),dp(p))
    }
    private fun text(s:String, size:Float=16f, strong:Boolean=false) = TextView(host).apply {
        this.text=s; textSize=size; setTextColor(if (strong) dark else gray)
        if (strong) setTypeface(null,Typeface.BOLD)
    }
    private fun button(s:String, primary:Boolean=false) = Button(host).apply {
        text=s; textSize=17f; isAllCaps=false; minHeight=dp(54)
        background=rounded(if (primary) emerald else pale)
        setTextColor(if (primary) Color.WHITE else emerald)
    }
    private fun add(parent:LinearLayout,v:View,space:Int=10) {
        parent.addView(v, LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(space)})
    }
    fun createView():View {
        container.background=rounded(Color.WHITE)
        add(container,text("🎬 VİDEO YAP",24f,true),14)
        imageView.scaleType=ImageView.ScaleType.FIT_CENTER
        imageView.visibility=View.GONE
        container.addView(imageView,LinearLayout.LayoutParams(-1,dp(150)).apply{bottomMargin=dp(8)})
        imageStatus.visibility=View.GONE
        add(container,imageStatus,4)
        add(container,button("📷 FOTOĞRAF SEÇ").apply {
            textSize=21f; minHeight=dp(72)
            setOnClickListener{pick("image/*",IMAGE_REQUEST)}
        },12)
        soundStatus.visibility=View.GONE
        add(container,soundStatus,4)
        songList.visibility=View.GONE
        add(container,songList,1)
        add(container,button("🎵 MÜZİK SEÇ").apply {
            textSize=21f; minHeight=dp(72)
            setOnClickListener{pick("audio/*",SOUND_REQUEST)}
        },14)
        // One minute, from the exact beginning, no hidden auto-seek or confusing controls.
        minute=1
        startAt=0L
        add(container,text("Video süresi (dakika, 1-4)", 17f, true),4)
        add(container,durationInput,12)
        add(container,make.apply {
            textSize=22f; minHeight=dp(78)
            setOnClickListener{generate()}
        },8)
        abort.visibility=View.GONE
        abort.setOnClickListener{runner.cancel();handler.removeCallbacks(poll);finish("İptal edildi.")}
        add(container,abort,6)
        progress.visibility=View.GONE;progress.max=100
        progress.progressTintList=ColorStateList.valueOf(emerald)
        add(container,progress,8)
        state.text=""
        add(container,state,8)
        open.visibility=View.GONE
        open.setOnClickListener{openVideo()}
        add(container,open,0)
        refreshMusic()
        return container
    }
    private fun selectMinute() {
        for((n,b) in minuteButtons){
            b.background=rounded(if(minute==n)emerald else pale)
            b.setTextColor(if(minute==n)Color.WHITE else emerald)
        }
    }
    private fun pick(type:String,code:Int) {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            this.type=type
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        host.startActivityForResult(intent,code)
    }
    fun result(request:Int,result:Int,data:Intent?) {
        if(result!=Activity.RESULT_OK)return
        val uri=data?.data?:return
        try{host.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){}
        if(request==IMAGE_REQUEST){
            image=uri;imageView.setImageURI(uri);imageView.visibility=View.VISIBLE
            imageStatus.text="✓ Fotoğraf seçildi"; imageStatus.visibility=View.VISIBLE
        }else if(request==SOUND_REQUEST) {
            selectAudio(uri,"Müzik seçildi")
        }
    }
    fun refreshMusic(last:AudioEntry?=null) {
        if(last!=null)selectAudio(last.uri,"Müzik seçildi")
        val records=try{storage.list().take(12)}catch(_:Exception){emptyList()}
        songList.removeAllViews()
        if(records.isEmpty())add(songList,text("MP3 yoksa önce üstten indir.",14f),5)
        else{
            if(sound==null)selectAudio(records.first().uri,"İndirilen MP3")
            records.forEachIndexed{index,entry->
                val chosen=sound==entry.uri
                add(songList,button(if(chosen)"✓ Müzik "+(index+1)+" seçili" else "Müzik "+(index+1)+" seç").apply {
                    textSize=15f;setOnClickListener{selectAudio(entry.uri,"İndirilen müzik "+(index+1));refreshMusic()}
                },5)
            }
        }
    }
    fun selectSavedCut(uri: Uri) {
        selectAudio(uri, "Kesilen müzik hazır")
    }

    private fun chooseStart(value: Long) {
        stopPreview()
        startAt = IntroSelection.clamp(value, soundLength)
        seek.progress = (startAt / IntroSelection.STEP_MS).toInt()
        introStatus.text = ""
    }

    private fun selectAudio(uri:Uri,name:String) {
        stopPreview()
        sound=uri
        soundStatus.text="✓ "+name
        soundStatus.visibility=View.VISIBLE
        val serial=++detectionJob
        soundLength=try {
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(host,uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
            } finally { retriever.release() }
        } catch(_:Exception) { 0L }
        suggestedStart=null
        suggestListen.visibility=View.GONE
        suggestApply.visibility=View.GONE
        seek.max = (IntroSelection.maxStart(soundLength) / IntroSelection.STEP_MS).toInt()
        chooseStart(0L)
        // Simple mode: never guess, shift, or cut the start without user input.
    }

    private fun preview() {
        val uri=sound?:run { state.text="Önce müzik seç.";return }
        listenFrom(IntroSelection.clamp(startAt,soundLength),uri)
    }

    private fun listenFrom(start:Long, selected:Uri?=sound) {
        val uri=selected?:run { state.text="Önce müzik seç.";return }
        stopPreview()
        val id=++token
        try {
            val media=MediaPlayer()
            player=media
            media.setDataSource(host,uri)
            media.setOnSeekCompleteListener {
                if(player===it&&id==token) {
                    it.start()
                    listen.text="■ Önizlemeyi durdur"
                    handler.postDelayed({ if(id==token)stopPreview() },8000)
                }
            }
            media.setOnPreparedListener {
                // Wait for exact seek completion before playing, not from song zero.
                val actual=IntroSelection.clamp(start,soundLength)
                if(actual==0L) {
                    it.start()
                    listen.text="■ Önizlemeyi durdur"
                    handler.postDelayed({ if(id==token)stopPreview() },8000)
                } else {
                    it.seekTo(actual,MediaPlayer.SEEK_CLOSEST)
                }
            }
            media.setOnErrorListener { _,_,_->stopPreview();state.text="Ses açılamadı.";true }
            media.prepareAsync()
        } catch(_:Exception) { stopPreview();state.text="Ses açılamadı." }
    }

    private fun stopPreview() {
        token++
        try { player?.release() } catch(_:Exception) {}
        player=null
        listen.text="▶ Seçilen başlangıcı dinle"
    }

    private fun generate() {
        val img=image?:run{state.text="Önce fotoğraf seç.";return}
        val mp3=sound?:run{state.text="Önce müzik seç.";return}
        if(runner.busy)return
        val chosenMinutes = durationInput.text.toString().toIntOrNull()
        if(chosenMinutes == null || chosenMinutes !in 1..4) {
            state.text = "Video süresini 1 ile 4 dakika arasında gir."
            return
        }
        minute = chosenMinutes
        stopPreview();make.isEnabled=false;abort.visibility=View.VISIBLE
        progress.progress=0;progress.visibility=View.VISIBLE;state.text="Video hazırlanıyor. Bekle..."
        try{
            runner.export(img,mp3,minute,startAt,soundLength,
                onSaved={uri->videoUri=uri;finish("✓ Video hazır! Galeriye kaydedildi.");open.visibility=View.VISIBLE},
                onError={error->finish("Video yapılamadı: "+error)}
            )
            handler.post(poll)
        }catch(e:Exception){finish(e.message?:"Video başlatılamadı.")}
    }
    private fun finish(result:String) {
        make.isEnabled=true;abort.visibility=View.GONE;progress.visibility=View.GONE
        state.text=result;handler.removeCallbacks(poll)
    }
    private fun openVideo() {
        val uri=videoUri?:return
        try {
            val intent=Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri,"video/mp4");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            host.startActivity(Intent.createChooser(intent,"Videoyu aç"))
        }catch(_:Exception){Toast.makeText(host,"Galeride videona bak.",Toast.LENGTH_LONG).show()}
    }
    fun release() {
        stopPreview();handler.removeCallbacksAndMessages(null)
        if(runner.busy)runner.cancel()
    }
}
