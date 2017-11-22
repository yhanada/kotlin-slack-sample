package com.github.yhanada.slackbotsample

import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.toJsonArray
import com.github.yhanada.slackbotsample.calendar.GoogleCalendar
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import okhttp3.*

class SlackBot(private val token: String, private val userId: String) {

    lateinit var botId: String
    lateinit var botName: String

    data class CalendarField(val title: String, val value: String)

    data class CalendarItem(val title: String, val fields: List<CalendarField>, val color: String ="good") {
        companion object {
            private const val FORMAT = "<!date^%d^ {date_num} {time}|%s >"
            fun create(event: Event): CalendarItem {
                val fields = listOf("start", "end").map {
                    val dt: EventDateTime = event[it] as EventDateTime
                    CalendarField(it.toUpperCase(), String.format(FORMAT, dt.dateTime.value / 1000, dt.toString()))
                }.toList()
                return CalendarItem(event.summary, fields)
            }
        }
    }

    fun connect() {
        val url = connectUrl +
                "?token=$token" +
                "&simple_latest=true" +
                "&no_unreads=true"
        val request = Request.Builder()
                .url(url)
                .get().build()

        val responseText = okhttpclient
                .newCall(request)
                .execute()
                .body()
                ?.string()

        val response = parser.parse(responseText)
        if (response["ok"].asBoolean) {
            botId = response["self"]["id"].asString
            botName = response["self"]["name"].asString

            val req = Request.Builder().url(response["url"].asString).build()
            okhttpclient.newWebSocket(req, SlackEventListener())
            okhttpclient.dispatcher().executorService().shutdown()
        } else {
            logger.error("Failed to connect: $response")
        }
    }

    private fun createMessageBodyBuilder(text: String): FormBody.Builder {
        return FormBody.Builder().apply {
            add("text", text)
        }
    }

    private fun createMessageBodyBuilder(items: List<CalendarItem>): FormBody.Builder {
        return FormBody.Builder().apply {
            add("text", "予定です")

            val attachments = items.map { gson.toJsonTree(it) }.toJsonArray()
            add("attachments", gson.toJson(attachments))
        }
    }

    private fun postMessage(body: FormBody.Builder, channel: String) {
        body.add("token", token)
        body.add("username", botName)
        body.add("channel", channel)
        body.add("parse", "none")
        body.add("as_user", true.toString())

        val response = okhttpclient.newCall(postMessageRequestBuilder
                .post(body.build())
                .build())
                .execute()

        val resultObject = parser.parse(response.body()?.string())
        logger.debug("result:$resultObject")
    }

    private fun handleMessage(t: JsonObject) {
        if (t.has("message"))
            handleMessage(t["message"].asJsonObject)
        else {
            val text = t["text"].asString
            if (botId !in text)
                return

            val body: FormBody.Builder? = when {
                "hello" in text -> createMessageBodyBuilder("はろー")
                "ping" in text -> createMessageBodyBuilder("pong")
                "予定" in text -> {
                    val list = GoogleCalendar(userId).getEventItemss().map {
                        CalendarItem.create(it)
                    }.toList()

                    when {
                        list.isNotEmpty() -> createMessageBodyBuilder(list)
                        else -> createMessageBodyBuilder("予定はありません")
                    }
                }
                else -> null
            }
            body?.let {
                postMessage(it, t["channel"].asString)
            }
        }
    }

    private fun processMessage(json: JsonObject) {
        when (json["type"].asString) {
            "message" -> {
                handleMessage(json)
            }
            else -> {
                logger.info("Unsupported: ${json["type"]}")
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
            logger.debug ("onClosed $reason")
        }
    }

    companion object {
        val apiUrl = "https://slack.com/api"
        val connectUrl = "$apiUrl/rtm.connect"
        val postMessageRequestBuilder = Request.Builder().url("$apiUrl/chat.postMessage")
        val gson = Gson()
        val parser = JsonParser()
        val okhttpclient = OkHttpClient()
        val logger = KotlinLogging.logger{}
    }
}