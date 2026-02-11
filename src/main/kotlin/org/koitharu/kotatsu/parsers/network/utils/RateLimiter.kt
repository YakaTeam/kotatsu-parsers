package org.koitharu.kotatsu.parsers.network.utils

import okhttp3.Call
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

public class RateLimiter(
	private val permits: Int,
	private val periodMs: Long
) {
	private val timestamps = ArrayDeque<Long>(permits)
	private val lock = ReentrantLock(true) // fair lock
	private val condition = lock.newCondition()

	public fun acquire(call: Call, url: String = ""): Long = lock.withLock {
		var now = System.currentTimeMillis()

		while (true) {
			while (timestamps.isNotEmpty() && now - timestamps.first() >= periodMs) {
				timestamps.removeFirst()
			}

			if (timestamps.size < permits) break

			if (call.isCanceled()) throw IOException("Canceled")
			val oldestRequest = timestamps.first()
			val waitTime = periodMs - (now - oldestRequest)

			if (waitTime > 0) {
				condition.await(waitTime, TimeUnit.MILLISECONDS)
			}
			now = System.currentTimeMillis()
		}

		val timestamp = System.currentTimeMillis()
		timestamps.addLast(timestamp)
		return timestamp
	}

	public fun release(timestamp: Long): Boolean = lock.withLock {
		val removed = timestamps.remove(timestamp)
		if (removed) {
			condition.signalAll()
		}
		removed
	}
}
