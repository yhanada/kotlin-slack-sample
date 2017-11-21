package com.github.yhanada.slackbotsample

object Main {

    @JvmStatic fun main(args: Array<String>) {
        val env = System.getenv()

        val token = env["API_TOKEN"]!!
        val userId = env["USER_ID"]!!

        val bot = SlackBot(token, userId)
        bot.connect()

    }
}