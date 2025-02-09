package com.cofbro.qian.friend.chat

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import cn.leancloud.im.v2.LCIMConversation
import cn.leancloud.im.v2.LCIMMessage
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.cofbro.hymvvmutils.base.BaseActivity
import com.cofbro.qian.R
import com.cofbro.qian.databinding.ActivityChatBinding
import com.cofbro.qian.friend.FriendFragment
import com.cofbro.qian.friend.im.IMClientUtils
import com.cofbro.qian.friend.im.IMessageDispatchEvent
import com.cofbro.qian.friend.im.MessageSubscriber
import com.cofbro.qian.main.MainActivity
import com.cofbro.qian.utils.CacheUtils
import com.cofbro.qian.utils.Constants
import com.cofbro.qian.utils.KeyboardUtil
import com.cofbro.qian.utils.MsgFactory
import com.cofbro.qian.view.TipDialog
import com.hjq.toast.ToastUtils

class ChatActivity : BaseActivity<ChatViewModel, ActivityChatBinding>(), IMessageDispatchEvent {
    private var refreshing = false
    private var avatarUrl = ""
    private var username = ""
    private var pos = 0
    private var conv: LCIMConversation? = null
    private var msgData = arrayListOf<LCIMMessage>()
    private var mAdapter: ChatAdapter? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        changeNavigationResponsively()
        initWork()
        initArgs()
        doNetwork()
        initView()
        initEvent()
    }

    private fun initWork() {
        MessageSubscriber.subscribe(this)
        registerKeyboardHeight()
    }

    override fun onDestroy() {
        super.onDestroy()
        MessageSubscriber.unsubscribe(this)
        unregisterKeyboardHeight()
        conv?.read()
    }

    override fun onMessage(conv: LCIMConversation, message: LCIMMessage?) {
        insertMsg(message)
    }

    override fun getConversationId(): String {
        return conv?.conversationId ?: ""
    }

    private fun initArgs() {
        avatarUrl = intent.getStringExtra("avatar") ?: ""
        username = intent.getStringExtra("username") ?: ""
        pos = intent.getIntExtra("pos", 0)
        conv = CacheUtils.conv[Constants.Cache.CONV]
    }

    private fun doNetwork() {
        requestHistoryMessageFirstly()
    }

    private fun initView() {
        initUserInfo()
        initRecyclerView()
        initRefreshLayout()
    }

    private fun initRefreshLayout() {
        binding?.refreshLayout?.apply {
            setOnRefreshListener {
                autoRefresh()
                refreshing = true
                requestHistoryMessage()
            }
        }
    }

    private fun initRecyclerView() {
        mAdapter = ChatAdapter(conv, avatarUrl)
        binding?.rvChat?.apply {
            adapter = mAdapter
            layoutManager = StaggeredGridLayoutManager(1, StaggeredGridLayoutManager.VERTICAL)

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy < 0) {
                        KeyboardUtil.hideKeyboard(this@ChatActivity, binding!!.root)
                    }
                }
            })
        }
    }

    private fun initUserInfo() {
        binding?.topName?.text = username
        // 头像
        val options = RequestOptions().transform(
            CenterCrop(),
            RoundedCorners(25)
        )
        Glide.with(this)
            .load(avatarUrl)
            .apply(options)
            .into(binding!!.topAvatar)
    }

    private fun initEvent() {
        binding?.topBack?.setOnClickListener {
            finish()
        }

        binding?.ivSend?.setOnClickListener {
            val msg = binding?.etSendMsg?.text.toString()
            sendMsg(msg)
        }

        binding?.ivMoreOption?.setOnClickListener {
            showPopMenu(it)
        }
    }

    private fun registerKeyboardHeight() {
        val layout = binding?.root?.layoutParams as? MarginLayoutParams
        KeyboardUtil.registerKeyboardHeightListener(
            this,
            object : KeyboardUtil.KeyboardHeightListener {
                override fun onKeyboardHeightChanged(height: Int) {
                    layout?.bottomMargin = height + KeyboardUtil.mNavHeight
                    binding?.root?.layoutParams = layout
                    scrollToNewestOne()
                }
            })
    }

    private fun unregisterKeyboardHeight() {
        KeyboardUtil.unregisterKeyboardHeightListener(this)
    }

    private fun requestHistoryMessageFirstly(count: Int = 0) {
        conv?.let {
            var realCount = it.unreadMessagesCount.takeIf { c ->
                c != 0
            } ?: 20
            if (count != 0) {
                realCount = count
            }
            IMClientUtils.queryHistoryMessage(it, realCount,
                onSuccess = { msg ->
                    msg?.let {
                        insertRangedData(msg)
                    }
                }, onError = {
                    ToastUtils.show("历史数据拉取失败！")
                    finishRefresh()
                }
            )
        }
    }

    private fun requestHistoryMessage(count: Int = 15) {
        conv?.let { conversation ->
            val firstMsg = msgData.getOrNull(0)
            firstMsg?.let {
                IMClientUtils.queryHistoryMessage(conversation, count, it,
                    onSuccess = { msg ->
                        msg?.let {
                            insertRangedData(msg)
                        }
                    }, onError = {
                        ToastUtils.show("历史数据拉取失败！")
                        finishRefresh()
                    }
                )
            }
        }
    }

    private fun insertRangedData(msg: List<LCIMMessage>) {
        if (msg.isNotEmpty()) {
            // 手动拉取历史数据
            if (refreshing) {
                refreshing = false
                msgData.addAll(0, msg)
                mAdapter?.insertDataAtFirst(msg)
            } else {
                // 首次进入拉取历史数据
                msgData.addAll(msg)
                mAdapter?.setData(msg)
                scrollToNewestOne()
            }
        }
        finishRefresh()
    }

    private fun finishRefresh() {
        binding?.refreshLayout?.finishRefresh()
    }

    private fun insertMsg(msg: LCIMMessage?) {
        msg?.let {
            mAdapter?.insertMsg(it)
            scrollToNewestOne()
        }
    }

    private fun scrollToNewestOne() {
        val data = mAdapter?.getAllMsg()
        if (!data.isNullOrEmpty()) {
            val layoutManager = binding?.rvChat?.layoutManager as? LinearLayoutManager
            if (layoutManager?.findLastCompletelyVisibleItemPosition() != data.size - 1) {
                binding?.rvChat?.scrollToPosition(data.size - 1)
            }
        }
    }

    private fun sendMsg(msg: String) {
        conv?.let {
            IMClientUtils.sendMsg(it, msg, onSuccess = {
                val lcMsg = MsgFactory.createNormalLCMessage(msg)
                insertMsg(lcMsg)
                clear()
            })
        }
    }

    private fun clear() {
        binding?.etSendMsg?.text?.clear()
        binding?.etSendMsg?.hint = "输入您的消息"
    }

    override fun onStop() {
        findFriendFragment()?.notifyConversationMsgChanged(conv)
        super.onStop()
    }

    private fun findFriendFragment(): FriendFragment? {
        return (CacheUtils.activities[Constants.Cache.MAIN_ACTIVITY] as? MainActivity)?.supportFragmentManager?.findFragmentByTag(
            "FriendFragment"
        ) as? FriendFragment
    }

    private fun changeNavigationResponsively() {
        binding?.root?.post {
            val windowInsects = ViewCompat.getRootWindowInsets(window.decorView)
            val height =
                windowInsects?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom
                    ?: 0
            updateLayoutParams(height)
        }
    }

    private fun updateLayoutParams(height: Int) {
        if (height > 80) {
            val layout = binding?.root?.layoutParams as? MarginLayoutParams
            layout?.bottomMargin = height
            binding?.root?.layoutParams = layout
        }
    }

    private fun showPopMenu(view: View) {
        val menu = PopupMenu(this, view)
        menu.gravity = Gravity.RIGHT
        menu.menuInflater.inflate(R.menu.pop_request_option, menu.menu)
        menu.setOnMenuItemClickListener {
            if (it.itemId == R.id.pop_request_sign) {
                menu.dismiss()
                showSignWithCookieTipDialog()
                return@setOnMenuItemClickListener true
            } else if (it.itemId == R.id.pop_request_homework) {
                menu.dismiss()
                return@setOnMenuItemClickListener true
            }
            false
        }
        menu.show()
    }

    private fun showSignWithCookieTipDialog() {
        TipDialog(this).apply {
            show()
            setCancelable(false)
            setTitle(context.resources.getString(R.string.chat_request_cookie_sign_title))
            setContent(context.resources.getString(R.string.chat_request_cookie_sign_content))
            setPositiveClickListener {
                sendCookieToFriend()
                dismiss()
            }

            setNegativeClickListener {
                dismiss()
            }
        }
    }

    private fun sendCookieToFriend() {
        val text = "好哥们er，帮我代签一次呗~"
        val map = hashMapOf<String, Any>()
        val who = IMClientUtils.getCntUser()?.objectId ?: ""
        val cookie = CacheUtils.cache[Constants.Login.COOKIES] ?: ""
        map[MsgFactory.who] = who
        map[MsgFactory.cookieSign] = cookie
        map[MsgFactory.agree] = ""
        conv?.let {
            IMClientUtils.sendMsg(it, text, map,
                onSuccess = {
                    val cookieCard = MsgFactory.createCookieSignLCMessage(text, cookie)
                    insertMsg(cookieCard)
                    clear()
                    saveCookieInLc(cookie)
                }, onError = {
                    ToastUtils.show("消息发送失败")
                }
            )
        }
    }

    private fun saveCookieInLc(cookie: String) {
        // 使用自己的objectId作为cardId
        IMClientUtils.createCookieCard(IMClientUtils.getCntUser()?.objectId + cookie, cookie)
    }
}