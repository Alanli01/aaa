package com.vcredit.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.AsyncTask
import android.os.Handler
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.telephony.SmsManager
import android.text.TextUtils
import android.util.Log
import com.squareup.okhttp.MediaType
import com.squareup.okhttp.OkHttpClient
import com.squareup.okhttp.Request
import com.squareup.okhttp.RequestBody
import java.util.concurrent.TimeUnit
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.gson.annotations.Expose
import com.vcredit.sms.permission.PermissionsActivity
import com.vcredit.sms.permission.PermissionsChecker
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.collections.ArrayList

/**
 * 服务器返回的数据
 */
open class Result(@Expose val IsSuccess: Boolean, @Expose val ErrorInfo: String = "")

open class MyMessage(var _id: String, @Expose var receiveMobile: String, @Expose var smsContent: String, var isSuccess: Boolean);
class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE = 11212
    lateinit var okHttpClient: OkHttpClient
    private var reciverSmsId = ArrayList<MyMessage>();
    fun showMessage(str: String) {
        Toast.makeText(this, str, Toast.LENGTH_SHORT).show()
    }

    private lateinit var phoneNum: String
    val sharedPreferences: SharedPreferences by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }
    private val editPhoneNum: EditText by lazy { findViewById(R.id.editPhone) as EditText }
    private val recyclerView: RecyclerView  by lazy { findViewById(R.id.recyclerView) as RecyclerView }
    private val btnListener: Button  by lazy { findViewById(R.id.btnListener) as Button }


    fun isValidMobileNo(MobileNo: String): Boolean {

        if (!TextUtils.isEmpty(MobileNo)) {
            val regPattern = "^1[3-9]\\d{9}$";
            return Pattern.matches(regPattern, MobileNo);
        }
        return false;
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && PermissionsChecker.lacksPermissions(this)) {
            PermissionsActivity.startActivityForResult(this, REQUEST_CODE);
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        phoneNum = sharedPreferences.getString("phoneNum", "");
        if (!TextUtils.isEmpty(phoneNum)) {
            editPhoneNum.setText(phoneNum)
        }
        okHttpClient = OkHttpClient()
        configOkHttpClient();
        btnListener.setOnClickListener {
            if (btnListener.text.equals("开始监听")) {
                if (!TextUtils.isEmpty(editPhoneNum.text.toString()) && isValidMobileNo(editPhoneNum.text.toString())) {
                    phoneNum = (findViewById(R.id.editPhone) as EditText).text.toString()
                } else {
                    showMessage("手机号不正确")
                    return@setOnClickListener
                }
                sharedPreferences.edit().putString("phoneNum", phoneNum).commit()
                registerMyContentObserver()
                showMessage("监听成功")
                editPhoneNum.isEnabled = false
                btnListener.setText("修改手机号")
            } else {
                unRegisterContentObserver()
                editPhoneNum.isEnabled = true
                btnListener.setText("开始监听")
            }
        }


        findViewById(R.id.sendMessage).setOnClickListener {
            val smsManager = SmsManager.getDefault()
            val pi = PendingIntent.getBroadcast(this, 0, Intent(), 0)
            try {
                smsManager.sendTextMessage("10001", null, "102", pi, null)
            } catch (e: Exception) {
                e.printStackTrace()
                showMessage("发送失败")
                return@setOnClickListener
            }
            showMessage("发送成功")
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = MyAdapter(this, reciverSmsId)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
    }


    inner class MyAdapter : RecyclerView.Adapter<MyAdapter.MyViewHolder> {
        inner class MyViewHolder : RecyclerView.ViewHolder {
            var phoneNumber: TextView
            var smsContent: TextView
            var btnSend: Button

            constructor(itemView: View) : super(itemView) {
                phoneNumber = itemView.findViewById(R.id.phoneNumber) as TextView
                smsContent = itemView.findViewById(R.id.smsContent) as TextView
                btnSend = itemView.findViewById(R.id.btnSend) as Button
            }
        }

        lateinit var msgs: ArrayList<MyMessage>
        lateinit var mContexnt: Context

        constructor(context: Context, data: ArrayList<MyMessage>) {
            this.mContexnt = context
            this.msgs = data
        }

        override fun onBindViewHolder(holder: MyViewHolder?, position: Int) {
            val smsObj = msgs.get(position)
            holder?.phoneNumber!!.text = smsObj.receiveMobile
            holder?.smsContent!!.text = smsObj.smsContent
            if (smsObj.isSuccess) {
                holder?.btnSend!!.text = "成功"
                holder?.btnSend!!.setOnClickListener(null)
            } else {
                holder?.btnSend!!.text = "失败"
                holder?.btnSend!!.setOnClickListener {
                    upLoadSms(smsObj)
                }
            }
        }

        fun changeMsgState(_id: String, isSuccess: Boolean) {
            msgs.forEach {
                if (it._id.equals(_id)) {
                    it.isSuccess = isSuccess
                }
            }
            notifyDataSetChanged()
            recyclerView.scrollToPosition(recyclerView.adapter.getItemCount() - 1);
        }

        override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): MyViewHolder {
            return MyViewHolder(LayoutInflater.from(mContexnt).inflate(R.layout.item_sms, parent, false))
        }

        override fun getItemCount(): Int {
            return msgs.size
        }
    }

    private fun configOkHttpClient() {
        okHttpClient.setConnectTimeout(30000, TimeUnit.MILLISECONDS)
        okHttpClient.setWriteTimeout(30000, TimeUnit.MILLISECONDS)
        okHttpClient.setReadTimeout(30000, TimeUnit.MILLISECONDS)
    }

    lateinit var observer: SmsObserver

    var initMyMsgCount = 0;
    fun initMyMsgCount() {
        initMyMsgCount = getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, "date desc").count
        Log.d("tag", "initMyMsgCount = $initMyMsgCount")
    }

    fun registerMyContentObserver() {
        initMyMsgCount();
        observer = SmsObserver(Handler(), this)
        contentResolver.registerContentObserver(Uri.parse("content://sms/"), true, observer)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            registerMyContentObserver()
        }
    }

    fun unRegisterContentObserver() {
        contentResolver.unregisterContentObserver(observer)
    }

    val myLock = ReentrantLock()
    var id =""
    inner class SmsObserver(handler: Handler, private val mContext: Context) : ContentObserver(handler) {
        val MMSSMS_ALL_MESSAGE_URI = Uri.parse("content://sms/inbox")
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            myLock.lock()
            if(myLock.tryLock()) {
                try {
                    var cursor: Cursor? = null
                    try {
                        //读取收件箱中的短信
                        cursor = mContext.getContentResolver().query(MMSSMS_ALL_MESSAGE_URI, null, null, null, "date desc")
                        val count = cursor.count
                        Log.d("tag", "mMessageCount = ${initMyMsgCount}  ==  count = ${count} ")
                        if (count <= initMyMsgCount) {
                            //不是最新短信返回
                            initMyMsgCount = count
                            return
                        }
                        initMyMsgCount = count
                        if (cursor != null) {
                            cursor!!.moveToNext()
                            val _id = cursor.getString(cursor.getColumnIndex("_id"))
                            val address = cursor.getString(cursor.getColumnIndex("address"))
                            val body = cursor!!.getString(cursor!!.getColumnIndex("body"))
                            Log.d("tag", "_id = ${_id}")
                            Log.d("tag", "id = ${id}")
                            if (!id.equals(_id)) {
                                val currentMsg = MyMessage(_id, phoneNum, body, false)
                                upLoadSms(currentMsg)
                                id = _id
                            }
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        if (cursor != null) cursor!!.close()
                    }
                } finally {
                    myLock.unlock()
                }
            }
        }

    }

    val serverUrl = "http://10.138.60.122:8008/smscenter/v1/appreceives";
    private fun upLoadSms(currentMsg: MyMessage) {
        if (currentMsg.smsContent.contains("$" + "_$")) {
            reciverSmsId.add(currentMsg)
            object : AsyncTask<MyMessage, Void, Result>() {
                override fun doInBackground(vararg params: MyMessage?): Result? {
                    val request = createRequestBuilder(serverUrl, currentMsg)
                    var responseStr = ""
                    try {
                        val e = okHttpClient.newCall(request).execute()
                        var code = e.code()
                        if(code == 200) {
                            responseStr = e.body().string()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    Log.d("tag response", responseStr)
                    if(responseStr.equals("")){
                        return null
                    }
                    return JsonUtils.json2Object(responseStr, Result::class.java)
                }

                override fun onPostExecute(result: Result?) {
                    super.onPostExecute(result)
                    if (result != null) {
                        if ("成功".equals(result.ErrorInfo)) {
                            currentMsg.isSuccess = true
                        } else {
                            currentMsg.isSuccess = false
                        }
                        Log.d("tag", "刷新数据")
                        showMessage(result.ErrorInfo)
                        notifyStateChange(currentMsg)
                    }
                }

            }.execute(currentMsg)
        } else {
            showMessage("不需要监听的短信不上传")
        }
    }

    private fun notifyStateChange(currentMsg: MyMessage) {
        (recyclerView.adapter as MyAdapter).changeMsgState(currentMsg._id, currentMsg.isSuccess)
    }

    fun createRequestBuilder(url: String, msg: MyMessage): Request {
        val requestStr = JsonUtils.toJson(msg)
        val builder = Request.Builder();
        builder.url(url)
        Log.d("tag request = ", requestStr)
        builder.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), requestStr))
        return builder.build()
    }

}