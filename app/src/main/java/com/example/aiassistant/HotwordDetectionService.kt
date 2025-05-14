package com.example.aiassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * خدمة اكتشاف الكلمات المفتاحية
 * تعمل هذه الخدمة في الخلفية للاستماع إلى كلمات مفتاحية محددة مثل "زمولي"
 * عند اكتشاف الكلمة المفتاحية، يتم استدعاء واجهة HotwordListener
 */
class HotwordDetectionService : Service() {
    private val TAG = "HotwordDetectService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "hotword_detection_channel"
    private val HOTWORD_MODEL_FILE = "model.tflite"
    
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var hotwordListener: HotwordListener? = null
    private var interpreter: Interpreter? = null
    private var detectionJob: Job? = null
    
    // تكوين الصوت
    private val sampleRate = 16000 // 16kHz
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // واجهة للاستجابة عند اكتشاف كلمة مفتاحية
    interface HotwordListener {
        fun onHotwordDetected()
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "خدمة اكتشاف الكلمات المفتاحية بدأت")
        
        // إعداد قناة الإشعارات للخدمة الأمامية (أندرويد 8.0+)
        createNotificationChannel()
        
        // تهيئة نموذج TensorFlow Lite
        try {
            initializeHotwordModel()
        } catch (e: Exception) {
            Log.e(TAG, "فشل في تهيئة نموذج الكلمات المفتاحية: ${e.message}")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "تم استدعاء onStartCommand")
        
        // بدء الخدمة كخدمة أمامية لضمان عدم إيقافها بواسطة النظام
        startForeground(NOTIFICATION_ID, createNotification())
        
        // بدء الاستماع للكلمات المفتاحية
        startHotwordDetection()
        
        // إذا تم إيقاف الخدمة، أعد تشغيلها
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopHotwordDetection()
        interpreter?.close()
        Log.d(TAG, "تم إيقاف خدمة اكتشاف الكلمات المفتاحية")
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null // هذه الخدمة غير مربوطة
    }
    
    /**
     * تعيين مستمع الكلمات المفتاحية
     */
    fun setHotwordListener(listener: HotwordListener) {
        this.hotwordListener = listener
        Log.d(TAG, "تم تعيين مستمع الكلمات المفتاحية")
    }
    
    /**
     * إنشاء قناة إشعارات للخدمة الأمامية
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "اكتشاف الكلمات المفتاحية"
            val descriptionText = "خدمة اكتشاف الكلمات المفتاحية للاستماع إلى كلمات مثل زمولي"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * إنشاء إشعار للخدمة الأمامية
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("مساعد زمولي")
            .setContentText("الاستماع للكلمات المفتاحية...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * تهيئة نموذج اكتشاف الكلمات المفتاحية
     */
    private fun initializeHotwordModel() {
        try {
            val assetFileDescriptor = assets.openFd(HOTWORD_MODEL_FILE)
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            
            val modelBuffer: MappedByteBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                startOffset,
                declaredLength
            )
            
            interpreter = Interpreter(modelBuffer)
            Log.d(TAG, "تم تحميل نموذج الكلمات المفتاحية بنجاح")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحميل نموذج الكلمات المفتاحية: ${e.message}")
        }
    }
    
    /**
     * بدء اكتشاف الكلمات المفتاحية
     */
    private fun startHotwordDetection() {
        if (isListening) return
        
        isListening = true
        
        detectionJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "فشل في تهيئة AudioRecord")
                    isListening = false
                    return@launch
                }
                
                audioRecord?.startRecording()
                Log.d(TAG, "بدأ التسجيل والاستماع للكلمات المفتاحية")
                
                val buffer = ShortArray(bufferSize)
                
                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (readSize > 0) {
                        if (detectHotword(buffer, readSize)) {
                            Log.d(TAG, "تم اكتشاف كلمة مفتاحية!")
                            withContext(Dispatchers.Main) {
                                hotwordListener?.onHotwordDetected()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في اكتشاف الكلمات المفتاحية: ${e.message}")
            } finally {
                stopRecording()
            }
        }
    }
    
    /**
     * إيقاف اكتشاف الكلمات المفتاحية
     */
    private fun stopHotwordDetection() {
        isListening = false
        detectionJob?.cancel()
        stopRecording()
    }
    
    /**
     * إيقاف التسجيل وتحرير الموارد
     */
    private fun stopRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
    
    /**
     * اكتشاف الكلمة المفتاحية في بيانات الصوت
     */
    private fun detectHotword(audioBuffer: ShortArray, readSize: Int): Boolean {
        if (interpreter == null) return false
        
        try {
            // تحويل بيانات الصوت إلى التنسيق المطلوب للنموذج
            val inputBuffer = prepareAudioData(audioBuffer, readSize)
            
            // إخراج النموذج
            val outputBuffer = Array(1) { FloatArray(2) } // افتراض وجود فئتين: كلمة مفتاحية وغير كلمة مفتاحية
            
            // تشغيل التوقع
            interpreter?.run(inputBuffer, outputBuffer)
            
            // التحقق من النتيجة (مثال: إذا كانت احتمالية الكلمة المفتاحية > 0.8)
            return outputBuffer[0][0] > 0.8f
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في معالجة الصوت: ${e.message}")
            return false
        }
    }
    
    /**
     * تحضير بيانات الصوت للنموذج
     */
    private fun prepareAudioData(audioBuffer: ShortArray, readSize: Int): ByteBuffer {
        // إنشاء ByteBuffer بحجم مناسب للنموذج
        val modelInputSize = 16000 // مثال: 1 ثانية من الصوت بمعدل 16kHz
        val inputBuffer = ByteBuffer.allocateDirect(modelInputSize * 2) // كل عينة 16 بت (2 بايت)
        inputBuffer.order(ByteOrder.nativeOrder())
        
        // نسخ بيانات الصوت إلى البافر
        for (i in 0 until minOf(readSize, modelInputSize)) {
            inputBuffer.putShort(audioBuffer[i])
        }
        
        // إعادة ضبط موضع البافر للقراءة
        inputBuffer.rewind()
        
        return inputBuffer
    }
}