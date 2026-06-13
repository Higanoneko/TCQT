package com.owo233.tcqt.hooks.func.activity

import android.app.Application
import com.owo233.tcqt.R
import com.owo233.tcqt.annotations.RegisterAction
import com.owo233.tcqt.ext.ActionProcess
import com.owo233.tcqt.ext.IAction
import com.owo233.tcqt.hooks.base.loadOrThrow
import com.owo233.tcqt.hooks.helper.CustomMenu
import com.owo233.tcqt.hooks.helper.OnMenuBuilder
import com.owo233.tcqt.internals.QQInterfaces
import com.owo233.tcqt.utils.hook.MethodHookParam
import com.owo233.tcqt.utils.hook.hookAfter
import com.owo233.tcqt.utils.hook.paramCount
import com.owo233.tcqt.utils.log.Log
import com.owo233.tcqt.utils.reflect.getFields
import com.owo233.tcqt.utils.reflect.getMethods
import com.owo233.tcqt.utils.reflect.new
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact
import com.tencent.qqnt.msg.api.IMsgService
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

@RegisterAction
class EditResendTextMessage : IAction, OnMenuBuilder {

    override val name: String get() = "快捷编辑重发消息"
    override val desc: String get() = "长按自己发送的文本消息显示重新编辑按钮。"
    override val uiTab: String get() = "界面"
    override val key: String get() = "edit_resend_text_message"

    override val targetComponentTypes: Array<String>
        get() = REEDIT_MENU_COMPONENTS

    override fun onRun(app: Application, process: ActionProcess) {
        runCatching {
            hookRevokeGrayTipReedit()
        }.onFailure { e ->
            Log.e("edit resend failed: hook official gray tip re-edit", e)
        }
    }

    override fun onGetMenuNt(msg: Any, componentType: String, param: MethodHookParam) {
        runCatching {
            addReeditMenuItem(msg, param)
        }.onFailure { e ->
            Log.e("edit resend failed: build menu item", e)
        }
    }

    private fun addReeditMenuItem(msg: Any, param: MethodHookParam) {
        val msgRecord = MsgRecordHelper.getMsgRecord(msg)
        if (!msgRecord.canReeditByOfficialRule()) return

        val item = CustomMenu.createItemIconNt(
            msg = msg,
            text = "重新编辑",
            icon = R.drawable.ic_item_edit_72dp,
            id = R.id.item_edit_to_send,
            click = click@{
                if (!quickReeditByOfficialFlow(param.thisObject, msg, msgRecord)) {
                    Log.e("edit resend failed: run official re-edit flow")
                }
            }
        )

        @Suppress("UNCHECKED_CAST")
        (param.result as? MutableList<Any>)?.add(0, item)
    }

    private fun MsgRecord.canReeditByOfficialRule(): Boolean {
        val currentUid = QQInterfaces.currentUid
        val serverTimeSeconds = QQInterfaces.getServiceTime() / 1000
        return currentUid.isNotEmpty() &&
            currentUid == senderUid &&
            editable &&
            msgTime + REEDIT_TIME_LIMIT_SECONDS > serverTimeSeconds
    }

    private fun hookRevokeGrayTipReedit() {
        val bindMethod = loadOrThrow(REVOKE_GRAY_TIPS_COMPONENT)
            .declaredMethods
            .firstOrNull { method ->
                method.paramCount == 3 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[2] == List::class.java
            }
            ?: error("revoke gray tips bind method not found")

        bindMethod.isAccessible = true
        bindMethod.hookAfter { param ->
            val grayTipsMsgItem = param.args.getOrNull(1) ?: return@hookAfter
            triggerPendingReedit(param.thisObject, grayTipsMsgItem)
        }
    }

    private fun quickReeditByOfficialFlow(component: Any, msg: Any, msgRecord: MsgRecord): Boolean {
        val revokeCheck = loadOrThrow(REVOKE_CHECK_EVENT).new(msg, true, 0, null)
        val eventBus = component.findOfficialEventBus(revokeCheck) ?: return false
        cleanupPendingReedit()
        pendingReedits[msgRecord.msgId] = PendingReedit.from(msgRecord)

        return runCatching {
            eventBus.publishOfficialEvent(revokeCheck)
            true
        }.getOrElse { e ->
            pendingReedits.remove(msgRecord.msgId)
            Log.e("edit resend failed: publish official revoke event", e)
            false
        }
    }

    private fun triggerPendingReedit(component: Any, grayTipsMsgItem: Any) {
        runCatching {
            triggerPendingReeditSafely(component, grayTipsMsgItem)
        }.onFailure { e ->
            Log.e("edit resend failed: trigger official gray tip re-edit", e)
        }
    }

    private fun triggerPendingReeditSafely(component: Any, grayTipsMsgItem: Any) {
        val msgRecord = MsgRecordHelper.getMsgRecord(grayTipsMsgItem)
        val pending = pendingReedits[msgRecord.msgId]
            ?: pendingReedits.values.firstOrNull { it.matches(msgRecord) }
            ?: return

        pendingReedits.remove(pending.msgId)
        recoverReeditDraft(component, msgRecord)
    }

    private fun cleanupPendingReedit() {
        val now = System.currentTimeMillis()
        pendingReedits.entries.removeIf { now - it.value.createdAt > PENDING_REEDIT_TIMEOUT_MS }
    }

    private fun recoverReeditDraft(component: Any, grayTipsMsgItem: MsgRecord) {
        val contact = Contact(
            grayTipsMsgItem.chatType,
            grayTipsMsgItem.peerUid,
            grayTipsMsgItem.guildId
        )
        val msgIds = arrayListOf(grayTipsMsgItem.msgId)

        val msgService = QRoute.api(IMsgService::class.java)
        val callback = object : IMsgOperateCallback {
            override fun onResult(result: Int, errMsg: String?, records: ArrayList<MsgRecord>?) {
                if (result != 0 || records.isNullOrEmpty()) {
                    Log.e("edit resend failed: get recalled msg (code=$result, msg=$errMsg)")
                    return
                }

                val recalledMsg = records.first()
                if (recalledMsg.msgType != MsgConstant.KMSGTYPEGRAYTIPS) return

                runCatching {
                    val recoverElements = loadOrThrow(RECOVER_MSG_ELEMENTS_INTENT)
                        .new(recalledMsg.elements)
                    val showKeyboard = loadOrThrow(SHOW_KEYBOARD_INTENT).new(0L)
                    val reeditSave = loadOrThrow(REEDIT_SAVE_INTENT).new(recalledMsg)
                    val eventBus = component.findOfficialEventBus(
                        recoverElements,
                        showKeyboard,
                        reeditSave
                    ) ?: error("official event bus not found")

                    eventBus.publishOfficialEvent(recoverElements)
                    eventBus.publishOfficialEvent(showKeyboard)
                    eventBus.publishOfficialEvent(reeditSave)
                }.onFailure { e ->
                    Log.e("edit resend failed: recover official re-edit draft", e)
                }
            }
        }

        runCatching {
            msgService.getRecallMsgsByMsgId(contact, msgIds, callback)
        }.onFailure { e ->
            Log.e("edit resend failed: call getRecallMsgsByMsgId", e)
        }
    }

    private fun Any.findOfficialEventBus(vararg events: Any): Any? {
        val visited = HashSet<Int>()
        val candidates = buildList {
            add(this@findOfficialEventBus)
            getMethods(true)
                .filter { it.parameterTypes.isEmpty() && it.name == "getMContext" }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        method.invoke(this@findOfficialEventBus)
                    }.getOrNull()?.let(::add)
                }
            getFields(true).forEach { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(this@findOfficialEventBus)
                }.getOrNull()?.let(::add)
            }
        }

        candidates.forEach { candidate ->
            if (candidate == null || !visited.add(System.identityHashCode(candidate))) return@forEach
            if (candidate.canPublishAll(events)) return candidate

            candidate.getMethods(true)
                .asSequence()
                .filter {
                    it.parameterTypes.isEmpty() &&
                        it.returnType != Void.TYPE &&
                        it.returnType.canPublishEventTypes(events)
                }
                .mapNotNull { method ->
                    runCatching {
                        method.isAccessible = true
                        method.invoke(candidate)
                    }.getOrNull()
                }
                .firstOrNull { eventBus ->
                    visited.add(System.identityHashCode(eventBus)) && eventBus.canPublishAll(events)
                }
                ?.let { return it }
        }
        return null
    }

    private fun Any.canPublishAll(events: Array<out Any>): Boolean {
        return events.all { findPublishMethod(it) != null }
    }

    private fun Class<*>.canPublishEventTypes(events: Array<out Any>): Boolean {
        return events.all { event ->
            getMethods(true).any { method ->
                method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(event.javaClass)
            }
        }
    }

    private fun Any.findPublishMethod(event: Any): Method? {
        return javaClass.getMethods(true).firstOrNull { method ->
            method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isAssignableFrom(event.javaClass)
        }?.apply { isAccessible = true }
    }

    private fun Any.publishOfficialEvent(event: Any) {
        val method = findPublishMethod(event) ?: error("publish method not found: ${event.javaClass.name}")
        method.invoke(this, event)
    }

    private object MsgRecordHelper {

        private val getMsgRecordMethod by lazy {
            loadOrThrow("com.tencent.mobileqq.aio.msg.AIOMsgItem")
                .getDeclaredMethod("getMsgRecord")
                .apply { isAccessible = true }
        }

        fun getMsgRecord(msgItem: Any): MsgRecord {
            if (msgItem is MsgRecord) return msgItem
            return getMsgRecordMethod.invoke(msgItem) as MsgRecord
        }
    }

    private data class PendingReedit(
        val msgId: Long,
        val msgSeq: Long,
        val msgTime: Long,
        val peerUid: String,
        val senderUid: String,
        val createdAt: Long
    ) {
        fun matches(msgRecord: MsgRecord): Boolean {
            return peerUid == msgRecord.peerUid &&
                senderUid == msgRecord.senderUid &&
                (msgSeq == msgRecord.msgSeq || msgTime == msgRecord.msgTime)
        }

        companion object {
            fun from(msgRecord: MsgRecord): PendingReedit {
                return PendingReedit(
                    msgId = msgRecord.msgId,
                    msgSeq = msgRecord.msgSeq,
                    msgTime = msgRecord.msgTime,
                    peerUid = msgRecord.peerUid,
                    senderUid = msgRecord.senderUid,
                    createdAt = System.currentTimeMillis()
                )
            }
        }
    }

    private companion object {
        val pendingReedits = ConcurrentHashMap<Long, PendingReedit>()
        const val REVOKE_CHECK_EVENT = "com.tencent.qqnt.aio.menu.MenuMsgEvent\$RevokeCheck"
        const val REEDIT_SAVE_INTENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.graptips.GrayTipsIntent\$ReEditMsgSave"
        const val RECOVER_MSG_ELEMENTS_INTENT =
            "com.tencent.mobileqq.aio.input.draft.InputDraftMsgIntent\$RecoverMsgElements"
        const val SHOW_KEYBOARD_INTENT =
            "com.tencent.mobileqq.aio.input.edit.InputEditTextMsgIntent\$ShowKeyboardMsgIntent"
        const val REVOKE_GRAY_TIPS_COMPONENT =
            "com.tencent.mobileqq.aio.msglist.holder.component.graptips.revoke.RevokeGrayTipsComponent"
        const val PENDING_REEDIT_TIMEOUT_MS = 30_000L
        const val REEDIT_TIME_LIMIT_SECONDS = 120L
        const val TEXT_COMPONENT = "com.tencent.mobileqq.aio.msglist.holder.component.text.AIOTextContentComponent"
        const val REPLY_COMPONENT = "com.tencent.mobileqq.aio.msglist.holder.component.reply.AIOReplyComponent"
        const val MIX_COMPONENT = "com.tencent.mobileqq.aio.msglist.holder.component.mix.AIOMixContentComponent"
        val REEDIT_MENU_COMPONENTS = arrayOf(
            TEXT_COMPONENT,
            REPLY_COMPONENT,
            MIX_COMPONENT
        )
    }
}
