package com.coldfier.aws.s3.internal.date

import java.util.*

/**
 * Function for current date in GMT-0 time zone receiving
 * @return current date that calculates to GMT-0 time zone
 */
fun getGmt0Date(): Date {
    val calendar = Calendar.getInstance()
    val date = calendar.time
    date.time = date.time - calendar.timeZone.rawOffset
    return date
}

/**
 * Function for [Date] to [String] converting
 * @return string date in "EEE, d MMM yyyy HH:mm:ss 'GMT'" format
 * @sample "Sun, 12 Mar 2023 00:00:00 GMT"
 */
fun Date.toRfc2822String(): String  = DateFormat.RFC_2822.format(this)

/**
 * Function for [Date] to [String] converting
 * @return string date in "yyyyMMdd'T'HHmmss'Z'" format
 * @see [ISO-8601]
 * @sample "20230312T000000Z"
 */
fun Date.toIso8601FullString(): String = DateFormat.ISO_8601_FULL.format(this)

/**
 * Function for [Date] to [String] converting
 * @return string date in "yyyyMMdd" format
 * @see [ISO-8601]
 * @sample "20230312"
 */
fun Date.toIso8601ShortString(): String = DateFormat.ISO_8601_SHORT.format(this)