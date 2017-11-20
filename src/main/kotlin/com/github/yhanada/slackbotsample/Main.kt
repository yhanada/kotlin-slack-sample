package com.github.yhanada.slackbotsample

import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.set
import com.github.yhanada.slackbotsample.calendar.GoogleCalendar
import com.google.api.client.util.DateTime
import com.google.gson.*
import okhttp3.*
import org.apache.commons.text.StringEscapeUtils

object Main {
    val apiUrl = "https://slack.com/api"
    val postMessageRequestBuilder = Request.Builder().url("$apiUrl/chat.postMessage")

    val gson = Gson()
    val parser = JsonParser()
    val okhttpclient = OkHttpClient()
    lateinit var selfID: String
    lateinit var selfName: String
    lateinit var token: String
    lateinit var userId: String

    data class MessageId(val ts: String, val channel: String)

    fun fillMessageBodyBuilder(text: String, channel: String): FormBody.Builder {
        val encodingBuilder = FormBody.Builder()
        encodingBuilder.add("token", token)
        encodingBuilder.add("text", "")
        encodingBuilder.add("channel", channel)
        encodingBuilder.add("parse", "full")
        val attachments = JsonArray()

        val resultAttachment = JsonObject()
        resultAttachment["text"] = text
        val markdownIn = JsonArray()
        markdownIn.add("text")
        resultAttachment["mrkdwn_in"] = markdownIn
        attachments.add(resultAttachment)

        encodingBuilder.add("attachments", gson.toJson(attachments))
        return encodingBuilder
    }

    @JvmStatic fun main(args: Array<String>) {
        val env = System.getenv()

        token = env["API_TOKEN"]!!
        userId = env["USER_ID"]!!
        val startRequest = Request.Builder()
                .url("https://slack.com/api/rtm.start" +
                        "?token=$token" +
                        "&simple_latest=true" +
                        "&no_unreads=true")
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
            okhttpclient.newWebSocket(req, SlackEventListener)
            okhttpclient.dispatcher().executorService().shutdown()
        } else {
            //logger.error { "Got non-ok start response: $startResponse" }
        }
    }

    fun postMessage(text: String, channel: String): MessageId? {
        val bodyBuilder = fillMessageBodyBuilder(text, channel)
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
                val list: MutableList<String> = mutableListOf()
                for (event in items) {
                    var start: DateTime? = event.start.dateTime
                    if (start == null) {
                        start = event.start.date
                    }
                    var end: DateTime? = event.end.dateTime
                    if (end == null) {
                        end = event.end.date
                    }
                    list.add(String.format("%s (Start:%s - End:%s)", event.summary, start, end))
                }
                val result = list.filter(String::isNotBlank)
                        .map(StringEscapeUtils::unescapeHtml4)
                        .joinToString("\n")
                postMessage(result, channel)!!
            } else {
                postMessage("no events", channel)!!
            }
        }
    }

    fun processMessage(json: JsonObject) {
        when (json.get("type").asString) {
            "message" -> {
                //logger.trace { "Got new message" }
                processMessage(json, json["channel"].asString)
            }
            else -> {
                //logger.info { t.toString() }
                return
            }
        }
    }

    object SlackEventListener: WebSocketListener() {
        override fun onOpen(webSocket: WebSocket?, response: Response?) {
            super.onOpen(webSocket, response)
        }

        override fun onFailure(webSocket: WebSocket?, t: Throwable?, response: Response?) {
            super.onFailure(webSocket, t, response)
        }

        override fun onClosing(webSocket: WebSocket?, code: Int, reason: String?) {
            super.onClosing(webSocket, code, reason)
        }

        override fun onMessage(webSocket: WebSocket?, text: String?) {
            super.onMessage(webSocket, text)
            val response = parser.parse(text)
            try {
                if (response.get("type").isJsonPrimitive) {
                    processMessage(response.asJsonObject)
                }
            } catch (throwable: Throwable) {
                //logger.error("Exception while processing event", throwable)
                System.out.println(throwable.message)
                throwable.printStackTrace()
            }
        }

        override fun onClosed(webSocket: WebSocket?, code: Int, reason: String?) {
            super.onClosed(webSocket, code, reason)
        }

    }
}