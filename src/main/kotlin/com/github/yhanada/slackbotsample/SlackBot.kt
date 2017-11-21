package com.github.yhanada.slackbotsample

import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.toJsonArray
import com.github.yhanada.slackbotsample.calendar.GoogleCalendar
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.model.Event
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import org.slf4j.LoggerFactory

class SlackBot(private val token: String, private val userId: String) {

    lateinit var selfID: String
    lateinit var selfName: String

    data class MessageId(val ts: String, val channel: String)

    data class CalendarField(val title: String, val value: String)

    data class CalendarItem(val title: String, val fields: List<CalendarField>, val color: String ="good") {
        companion object {
            private const val FORMAT = "<!date^%d^ {date_num} {time}|%s >"
            fun create(event: Event): CalendarItem {
                val fields = mutableListOf<CalendarField>()
                fields.add(CalendarField("Start", String.format(FORMAT, event.start.dateTime.value / 1000, event.start.dateTime.toString())))
                fields.add(CalendarField("End", String.format(FORMAT, event.end.dateTime.value / 1000, event.end.dateTime.toString())))
                return CalendarItem(event.summary, fields)
            }
        }
    }

    fun start() {
        val url = startUrl +
                "?token=$token" +
                "&simple_latest=true" +
                "&no_unreads=true"
        val startRequest = Request.Builder()
                .url(url)
                .get().build()

        val startResponseText = okhttpclient
                .newCall(startRequest)
                .execute()
                .body()
                ?.string()

        val startResponse = parser.parse(startResponseText)
        if (startResponse["ok"].asBoolean) {
            selfName = startResponse["self"]["name"].asString
            selfID = startResponse["self"]["id"].asString
            val req = Request.Builder().url(startResponse["url"].asString).build()
            okhttpclient.newWebSocket(req, SlackEventListener())
            okhttpclient.dispatcher().executorService().shutdown()
        } else {
            logger.error("Failed to start: $startRequest")
        }
    }

    fun fillMessageBodyBuilder(items: List<CalendarItem>, channel: String): FormBody.Builder {
        val encodingBuilder = FormBody.Builder()
        encodingBuilder.add("token", token)
        encodingBuilder.add("text", "予定です")
        encodingBuilder.add("channel", channel)
        encodingBuilder.add("parse", "none")

        if (items.isEmpty()) return encodingBuilder

        val attachments = items.map { gson.toJsonTree(it) }.toJsonArray()
        encodingBuilder.add("attachments", gson.toJson(attachments))
        return encodingBuilder
    }

    fun postMessage(items: List<CalendarItem>, channel: String): MessageId? {
        val bodyBuilder = fillMessageBodyBuilder(items, channel)
        bodyBuilder.add("username", selfName)
        val response = okhttpclient.newCall(postMessageRequestBuilder
                .post(bodyBuilder.build())
                .build()).execute()
        val resultObject = parser.parse(response.body()?.string())
        if (resultObject["ok"].asBoolean)
            return MessageId(resultObject["ts"].asString, channel)
        return null
    }


    fun processMessage(t: JsonObject, channel: String) {
        if (t.has("message"))
            processMessage(t["message"].asJsonObject, channel)
        else {
            val text = t["text"].asString
            if (selfID !in text)
                return
            println("text:" + text)
            val googleCalendar = GoogleCalendar(userId)
            val items = googleCalendar.getEventItemss()
            if (items.isNotEmpty()) {
                val list: MutableList<CalendarItem> = mutableListOf()
                for (event in items) {
                    var start: DateTime? = event.start.dateTime
                    if (start == null) {
                        start = event.start.date
                    }
                    var end: DateTime? = event.end.dateTime
                    if (end == null) {
                        end = event.end.date
                    }
                    var s: Long = event.start.dateTime.value / 1000
                    var e: Long = event.end.dateTime.value / 1000
                    list.add(CalendarItem.create(event))
                }
                postMessage(list, channel)!!
            } else {
                postMessage(emptyList(), channel)!!
            }
        }
    }

    fun processMessage(json: JsonObject) {
        when (json["type"].asString) {
            "message" -> {
                logger.info("New message")
                processMessage(json, json["channel"].asString)
            }
            else -> {
                logger.info("unsupported: ${json["type"]}")
            }
        }
    }

    inner class SlackEventListener: WebSocketListener() {
        override fun onOpen(webSocket: WebSocket?, response: Response?) {
            logger.debug("onOpen")
        }

        override fun onFailure(webSocket: WebSocket?, t: Throwable?, response: Response?) {
            logger.error("onFailure", t)
        }

        override fun onClosing(webSocket: WebSocket?, code: Int, reason: String?) {
            logger.debug("onClosing $reason")
        }

        override fun onMessage(webSocket: WebSocket?, text: String?) {
            logger.trace("onMessage $text")

            val response = parser.parse(text)
            try {
                if (response.get("type").isJsonPrimitive) {
                    processMessage(response.asJsonObject)
                }
            } catch (throwable: Throwable) {
                logger.error(throwable.message, throwable)
            }
        }

        override fun onClosed(webSocket: WebSocket?, code: Int, reason: String?) {
            logger.debug("onClosed $reason")
        }
    }

    companion object {
        val apiUrl = "https://slack.com/api"
        val startUrl = "$apiUrl/rtm.start"
        val postMessageRequestBuilder = Request.Builder().url("$apiUrl/chat.postMessage")
        val gson = Gson()
        val parser = JsonParser()
        val okhttpclient = OkHttpClient()
        val logger = LoggerFactory.getLogger(javaClass.simpleName)
    }
}