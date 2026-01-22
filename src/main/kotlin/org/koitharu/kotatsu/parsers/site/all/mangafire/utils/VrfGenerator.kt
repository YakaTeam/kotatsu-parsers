package org.koitharu.kotatsu.parsers.site.all.mangafire.utils

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

public object VrfGenerator {
	private val rc4Keys = mapOf(
		"l" to "FgxyJUQDPUGSzwbAq/ToWn4/e8jYzvabE+dLMb1XU1o=",
		"g" to "CQx3CLwswJAnM1VxOqX+y+f3eUns03ulxv8Z+0gUyik=",
		"B" to "fAS+otFLkKsKAJzu3yU+rGOlbbFVq+u+LaS6+s1eCJs=",
		"m" to "Oy45fQVK9kq9019+VysXVlz1F9S1YwYKgXyzGlZrijo=",
		"F" to "aoDIdXezm2l3HrcnQdkPJTDT8+W6mcl2/02ewBHfPzg="
	)

	private val seeds32 = mapOf(
		"A" to "yH6MXnMEcDVWO/9a6P9W92BAh1eRLVFxFlWTHUqQ474=",
		"V" to "RK7y4dZ0azs9Uqz+bbFB46Bx2K9EHg74ndxknY9uknA=",
		"N" to "rqr9HeTQOg8TlFiIGZpJaxcvAaKHwMwrkqojJCpcvoc=",
		"P" to "/4GPpmZXYpn5RpkP7FC/dt8SXz7W30nUZTe8wb+3xmU=",
		"k" to "wsSGSBXKWA9q1oDJpjtJddVxH+evCfL5SO9HZnUDFU8="
	)

	private val prefixKeys = mapOf(
		"O" to "l9PavRg=",
		"v" to "Ml2v7ag1Jg==",
		"L" to "i/Va0UxrbMo=",
		"p" to "WFjKAHGEkQM=",
		"W" to "5Rr27rWd"
	)

	private fun add8(n: Int): (Int) -> Int = { c -> (c + n) and 0xFF }
	private fun sub8(n: Int): (Int) -> Int = { c -> (c - n + 256) and 0xFF }
	private fun rotl8(n: Int): (Int) -> Int = { c -> ((c shl n) or (c ushr (8 - n))) and 0xFF }
	private fun rotr8(n: Int): (Int) -> Int = { c -> ((c ushr n) or (c shl (8 - n))) and 0xFF }

	private val scheduleC = listOf(
		sub8(223), rotr8(4), rotr8(4), add8(234), rotr8(7),
		rotr8(2), rotr8(7), sub8(223), rotr8(7), rotr8(6)
	)

	private val scheduleY = listOf(
		add8(19), rotr8(7), add8(19), rotr8(6), add8(19),
		rotr8(1), add8(19), rotr8(6), rotr8(7), rotr8(4)
	)

	private val scheduleB = listOf(
		sub8(223), rotr8(1), add8(19), sub8(223), rotl8(2),
		sub8(223), add8(19), rotl8(1), rotl8(2), rotl8(1)
	)

	private val scheduleJ = listOf(
		add8(19), rotl8(1), rotl8(1), rotr8(1), add8(234),
		rotl8(1), sub8(223), rotl8(6), rotl8(4), rotl8(1)
	)

	private val scheduleE = listOf(
		rotr8(1), rotl8(1), rotl8(6), rotr8(1), rotl8(2),
		rotr8(4), rotl8(1), rotl8(1), sub8(223), rotl8(2)
	)

	public fun generate(input: String): String {
		val encodedInput = URLEncoder.encode(input, "UTF-8").replace("+", "%20")
		var bytes = encodedInput.toByteArray(Charsets.UTF_8)

		bytes = rc4(atob(rc4Keys["l"]!!), bytes)
		bytes = transform(bytes, atob(seeds32["A"]!!), atob(prefixKeys["O"]!!), scheduleC)

		bytes = rc4(atob(rc4Keys["g"]!!), bytes)
		bytes = transform(bytes, atob(seeds32["V"]!!), atob(prefixKeys["v"]!!), scheduleY)

		bytes = rc4(atob(rc4Keys["B"]!!), bytes)
		bytes = transform(bytes, atob(seeds32["N"]!!), atob(prefixKeys["L"]!!), scheduleB)

		bytes = rc4(atob(rc4Keys["m"]!!), bytes)
		bytes = transform(bytes, atob(seeds32["P"]!!), atob(prefixKeys["p"]!!), scheduleJ)

		bytes = rc4(atob(rc4Keys["F"]!!), bytes)
		bytes = transform(bytes, atob(seeds32["k"]!!), atob(prefixKeys["W"]!!), scheduleE)

		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	private fun atob(str: String): ByteArray = Base64.getDecoder().decode(str)

	private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
		val s = IntArray(256) { it }
		var j = 0

		for (i in 0..255) {
			j = (j + s[i] + key[i % key.size].toInt().and(0xFF)) and 0xFF
			val temp = s[i]
			s[i] = s[j]
			s[j] = temp
		}

		val output = ByteArray(input.size)
		var i = 0
		j = 0
		for (k in input.indices) {
			i = (i + 1) and 0xFF
			j = (j + s[i]) and 0xFF
			val temp = s[i]
			s[i] = s[j]
			s[j] = temp
			val t = (s[i] + s[j]) and 0xFF
			val kByte = s[t]
			output[k] = (input[k].toInt() xor kByte).toByte()
		}
		return output
	}

	private fun transform(
		input: ByteArray,
		seed: ByteArray,
		prefix: ByteArray,
		schedule: List<(Int) -> Int>
	): ByteArray {
		val out = ByteArrayOutputStream()
		for (i in input.indices) {
			if (i < prefix.size) {
				out.write(prefix[i].toInt())
			}
			val inputByte = input[i].toInt() and 0xFF
			val seedByte = seed[i % 32].toInt() and 0xFF
			val xored = inputByte xor seedByte
			val transformed = schedule[i % 10](xored)
			out.write(transformed)
		}
		return out.toByteArray()
	}
}
