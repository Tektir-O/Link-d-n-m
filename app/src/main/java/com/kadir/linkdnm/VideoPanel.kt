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
    private val abort = button("İptal Et")
    private val listen = button("▶ Başlangıcı dinle")
    private val open = button("▶ Oluşturulan videoyu aç")
    private var minute = 1
    private var minuteButtons = mapOf<Int,Button>()
    private var image: Uri? = null
    private var sound: Uri? = null
    private var soundLength = 0L
    private var startAt = 0L
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
        add(container,text("🎬  VİDEO OLUŞTUR",23f,true),7)
        add(container,text("Fotoğraf + müzik = MP4 video"),17)
        add(container,text("1. Fotoğraf seç",19f,true),8)
        imageView.scaleType=ImageView.ScaleType.FIT_CENTER
        imageView.visibility=View.GONE
        container.addView(imageView,LinearLayout.LayoutParams(-1,dp(205)).apply{bottomMargin=dp(8)})
        add(container,imageStatus,7)
        add(container,button("📷 FOTOĞRAF SEÇ").apply{setOnClickListener{pick("image/*",IMAGE_REQUEST)}},18)
        add(container,text("2. Müzik seç",19f,true),8)
        add(container,soundStatus,7)
        add(container,text("İndirdiğin MP3'ler:",15f),7)
        add(container,songList,8)
        add(container,button("🎵 TELEFONDAN SES SEÇ").apply{setOnClickListener{pick("audio/*",SOUND_REQUEST)}},18)
        add(container,text("3. Şarkıcının başladığı yer",19f,true),8)
        add(container,text("Otomatik tahmin yanlışsa kaydır. Sesini dinleyebilirsin.",14f),8)
        add(container,introStatus,4)
        seek.max=60
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b:SeekBar?,value:Int,fromUser:Boolean) {
                if(fromUser){startAt=value*1000L;introStatus.text="Başlangıç: "+value+". saniye"}
            }
            override fun onStartTrackingTouch(b:SeekBar?){}
            override fun onStopTrackingTouch(b:SeekBar?){}
        })
        add(container,seek,6)
        add(container,listen.apply{setOnClickListener{preview()}},18)
        add(container,text("4. Video kaç dakika?",19f,true),10)
        val choices=LinearLayout(host).apply{orientation=LinearLayout.HORIZONTAL}
        minuteButtons=VideoPlan.minutes.associateWith{value->
            button(value.toString()+" dk").also{b->
                b.textSize=15f
                choices.addView(b,LinearLayout.LayoutParams(0,dp(52),1f).apply{rightMargin=dp(if(value==4)0 else 4)})
                b.setOnClickListener{minute=value;selectMinute()}
            }
        }
        selectMinute()
        add(container,choices,17)
        add(container,make.apply{setOnClickListener{generate()}},7)
        abort.visibility=View.GONE
        abort.setOnClickListener{runner.cancel();handler.removeCallbacks(poll);finish("Video iptal edildi.")}
        add(container,abort,7)
        progress.visibility=View.GONE;progress.max=100
        progress.progressTintList=ColorStateList.valueOf(emerald)
        add(container,progress,8)
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
            imageStatus.text="✓ Fotoğraf seçildi"
        }else if(request==SOUND_REQUEST) {
            selectAudio(uri,"Telefondan müzik seçildi")
        }
    }
    fun refreshMusic(last:AudioEntry?=null) {
        if(last!=null)selectAudio(last.uri,"Yeni indirilen MP3")
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
    private fun selectAudio(uri:Uri,name:String) {
        stopPreview()
        sound=uri;soundStatus.text="✓ "+name
        soundLength=try{
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(host,uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
            }finally{retriever.release()}
        }catch(_:Exception){0L}
        startAt=0;seek.progress=0
        seek.max=minOf(60,if(soundLength>2000)((soundLength-1500)/1000).toInt() else 60)
        introStatus.text="Şarkıcının başladığı yer aranıyor..."
        Thread{
            val suggestion=IntroFinder.estimateMs(host,uri)
            host.runOnUiThread{
                if(sound==uri&&!host.isDestroyed){
                    startAt=VideoPlan.introMs(suggestion,soundLength)
                    seek.progress=(startAt/1000).toInt()
                    introStatus.text=if(startAt==0L)"Tahmin: başlangıç 0. saniye" else "Tahmin: "+(startAt/1000)+". saniye"
                }
            }
        }.start()
    }
    private fun preview() {
        val uri=sound?:run{state.text="Önce müzik seç.";return}
        if(player!=null){stopPreview();return}
        try{
            val media=MediaPlayer()
            player=media;media.setDataSource(host,uri)
            val id=++token
            media.setOnPreparedListener{
                it.seekTo(startAt.toInt());it.start();listen.text="■ Durdur"
                handler.postDelayed({if(token==id)stopPreview()},8000)
            }
            media.setOnErrorListener{_,_,_->stopPreview();true}
            media.prepareAsync()
        }catch(_:Exception){stopPreview();state.text="Müzik açılamadı."}
    }
    private fun stopPreview() {
        token++
        try{player?.release()}catch(_:Exception){}
        player=null;listen.text="▶ Başlangıcı dinle"
    }
    private fun generate() {
        val img=image?:run{state.text="Önce fotoğraf seç.";return}
        val mp3=sound?:run{state.text="Önce müzik seç.";return}
        if(runner.busy)return
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
