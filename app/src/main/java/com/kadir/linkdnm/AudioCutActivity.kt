package com.kadir.linkdnm

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.content.Context
import android.view.*
import android.widget.*
import android.net.Uri
import android.media.MediaMetadataRetriever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.*

/** Non-destructive MP3 editor: split, select, remove, undo, then save a new file. */
class AudioCutActivity : Activity() {
    private data class Piece(val start: Int, val end: Int)
    private var duration = 210000
    private var first = 45000
    private var second = 135000
    private var pieces = mutableListOf<Piece>()
    private var selected = -1
    private val history = mutableListOf<List<Piece>>()
    private lateinit var wave: Wave
    private lateinit var info: TextView
    private lateinit var cut: Button
    private lateinit var delete: Button
    private lateinit var undo: Button
    private lateinit var save: Button
    private lateinit var entry: Uri
    private fun time(ms:Int) = "%02d:%02d".format(ms/60000,ms/1000%60)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        entry = Uri.parse(intent.getStringExtra("audio_uri") ?: run { finish(); return })
        val retriever=MediaMetadataRetriever()
        try { retriever.setDataSource(this,entry); duration=(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 210000).coerceAtLeast(1000) }
        catch (_:Exception) { }
        finally { try { retriever.release() } catch (_:Exception) {} }
        first=duration/4; second=duration*3/4
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(20,28,20,20);setBackgroundColor(Color.rgb(16,21,19)) }
        fun btn(t:String,click:()->Unit):Button=Button(this).apply { text=t;isAllCaps=false;setOnClickListener{click()} }
        val title=TextView(this).apply { text="✂ ŞARKIYI KES";textSize=25f;setTextColor(Color.WHITE) }
        root.addView(title)
        info=TextView(this).apply { textSize=16f;setTextColor(Color.rgb(160,224,198));setPadding(0,20,0,12) }
        root.addView(info)
        wave=Wave(this)
        root.addView(wave,LinearLayout.LayoutParams(-1,(180*resources.displayMetrics.density).toInt()))
        cut=btn("✂ KESMEYİ UYGULA") {
            if(pieces.isEmpty()) { pieces.addAll(listOf(Piece(0,first),Piece(first,second),Piece(second,duration)));selected=1;update() }
        }
        root.addView(cut)
        delete=btn("🗑 SEÇİLEN PARÇAYI SİL") {
            if(selected in pieces.indices) { history.add(pieces.toList());pieces.removeAt(selected);selected=-1;update() }
        }
        root.addView(delete)
        undo=btn("↶ GERİ AL") {
            if(history.isNotEmpty()) { pieces=history.removeAt(history.lastIndex).toMutableList();selected=-1;update() }
            else if(pieces.isNotEmpty()) { pieces.clear();selected=-1;update() }
        }
        root.addView(undo)
        save=btn("💾 MP3 OLARAK KAYDET") { export() }
        root.addView(save)
        val scroll=ScrollView(this);scroll.addView(root);setContentView(scroll);update()
    }
    private fun update() {
        info.text=if(pieces.isEmpty()) "Kesme noktaları: ${time(first)} - ${time(second)}"
        else "${pieces.size} parça kaldı. ${if(selected>=0) "Parça ${selected+1} seçildi." else "Silmek için parçaya dokun."}"
        cut.isEnabled=pieces.isEmpty();delete.isEnabled=selected in pieces.indices
        undo.isEnabled=pieces.isNotEmpty() || history.isNotEmpty()
        save.isEnabled=pieces.isNotEmpty()
        wave.invalidate()
    }
    private inner class Wave(ctx:Context):View(ctx) {
        private val paint=Paint(3)
        private var active=-1
        private fun x(t:Int)=paddingLeft+12f+(width-24f)*t/duration
        override fun onDraw(c:Canvas) {
            super.onDraw(c)
            val middle=height*.5f
            val bounds=if(pieces.isEmpty()) listOf(Piece(0,first),Piece(first,second),Piece(second,duration)) else pieces
            bounds.forEachIndexed { i,p ->
                val left=x(p.start);val right=x(p.end)
                paint.color=if(pieces.isNotEmpty()&&selected==i) Color.rgb(33,78,61) else Color.rgb(42,55,48)
                c.drawRoundRect(left,20f,right,height-20f,8f,8f,paint)
                var px=left+3
                while(px<right-2) {
                    val seed=(px*0.29).toDouble()
                    val amp=(12+abs(sin(seed)*cos(seed*.21))*min(height*.31f,48f).toDouble()).toFloat()
                    paint.color=if(i==1) Color.rgb(54,207,161) else Color.rgb(137,158,147)
                    c.drawRoundRect(px,middle-amp,px+3,middle+amp,2f,2f,paint)
                    px+=6
                }
                if(pieces.isNotEmpty()&&selected==i) { paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;c.drawRoundRect(left,20f,right,height-20f,8f,8f,paint);paint.style=Paint.Style.FILL }
            }
            if(pieces.isEmpty()) for(t in listOf(first,second)) {
                paint.color=Color.WHITE;paint.strokeWidth=4f;c.drawLine(x(t),10f,x(t),height-10f,paint)
                c.drawRoundRect(x(t)-10,2f,x(t)+10,24f,4f,4f,paint)
            }
        }
        override fun onTouchEvent(e:android.view.MotionEvent):Boolean {
            val t=((e.x-12f)/(width-24f)*duration).roundToInt().coerceIn(0,duration)
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if(pieces.isEmpty()) active=if(abs(t-first)<abs(t-second)) 0 else 1
                    else { selected=pieces.indexOfFirst { t>=it.start && t<=it.end };update() }
                    return true
                }
                MotionEvent.ACTION_MOVE -> if(pieces.isEmpty()) {
                    if(active==0) first=t.coerceIn(0,second-100)
                    else second=t.coerceIn(first+100,duration)
                    update();return true
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {active=-1;return true}
            }
            return true
        }
    }
    private fun export() {
        if(pieces.isEmpty()) return
        save.isEnabled=false;info.text="MP3 hazırlanıyor..."
        val selectedPieces=pieces.toList()
        Thread {
            try {
                val bytes=contentResolver.openInputStream(entry)?.use { it.readBytes() } ?: error("Dosya okunamadı")
                val output=Mp3FrameCutter.cut(bytes,selectedPieces.map { it.start.toLong() to it.end.toLong() })
                if(output.isEmpty()) error("Kesilecek ses bulunamadı")
                val name="video-yerelmedya0-192k-${System.currentTimeMillis()}.mp3"
                val saved=Mp3Storage(this).save(ByteArrayInputStream(output),output.size.toLong(),name,{false}){_,_->}
                runOnUiThread { info.text="✓ Düzenlenen MP3 kaydedildi: ${saved.name}";save.isEnabled=true;Toast.makeText(this,"MP3 Müziklerim'e kaydedildi",Toast.LENGTH_LONG).show() }
            } catch(e:Exception) {runOnUiThread {info.text="Kaydedilemedi: ${e.message}";save.isEnabled=true}}
        }.start()
    }
}

/** Frame-aligned MPEG Layer III trimming without re-encoding. */
object Mp3FrameCutter {
    fun cut(data:ByteArray,ranges:List<Pair<Long,Long>>):ByteArray {
        var p=0
        if(data.size>=10&&data[0]==73.toByte()&&data[1]==68.toByte()&&data[2]==51.toByte()) {
            p=10+((data[6].toInt() and 127) shl 21)+((data[7].toInt() and 127) shl 14)+((data[8].toInt() and 127) shl 7)+(data[9].toInt() and 127)
        }
        val out=ByteArrayOutputStream()
        var elapsed=0.0
        val rates=arrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320,0)
        val rates2=arrayOf(0,8,16,24,32,40,48,56,64,80,96,112,128,144,160,0)
        val freqs=intArrayOf(44100,48000,32000)
        while(p+4<=data.size) {
            val a=data[p].toInt() and 255;val b=data[p+1].toInt() and 255
            val c=data[p+2].toInt() and 255
            if(a!=255 || (b and 0xe0)!=0xe0) {p++;continue}
            val version=(b shr 3) and 3;val layer=(b shr 1) and 3
            val br=(c shr 4) and 15;val sr=(c shr 2) and 3
            if(version==1||layer!=1||sr==3||br==0||br==15){p++;continue}
            val hz=freqs[sr]/(if(version==3)1 else if(version==2)2 else 4)
            val kbps=if(version==3)rates[br] else rates2[br]
            val frame=(if(version==3)144 else 72)*kbps*1000/hz+(c shr 1 and 1)
            if(frame<=4||p+frame>data.size) break
            val frameMs=(if(version==3)1152.0 else 576.0)*1000/hz
            if(ranges.any { elapsed<it.second && elapsed+frameMs>it.first })out.write(data,p,frame)
            elapsed+=frameMs;p+=frame
        }
        return out.toByteArray()
    }
}
