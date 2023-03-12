package com.coldfier.aws.v2.retrofit.aws.s3.v2.client.internal

import com.coldfier.aws.s3.core.AwsHeader
import com.coldfier.aws.s3.internal.*
import com.coldfier.aws.s3.internal.date.getGmt0Date
import com.coldfier.aws.s3.internal.date.toRfc2822String
import com.coldfier.aws.s3.internal.hash.Hash
import com.coldfier.aws.s3.internal.hash.HmacHash
import com.coldfier.aws.s3.internal.request.body.bodyBytes
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.Date

internal class AwsSigningV2Interceptor(
    private val credentialsStore: AwsCredentialsStore,
    private val endpointPrefix: String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        var s3Request = signRequest(request)

        val response = chain.proceed(s3Request)

        val responseCode = response.code
        return if (responseCode == 403 || responseCode == 400) {
            runBlocking { credentialsStore.updateCredentials() }
            s3Request = signRequest(request)

            chain.proceed(s3Request)
        } else response
    }

    private fun signRequest(request: Request): Request {
        val awsHeaders = calculateAwsHeaders(request)

        return request.newBuilder()
            .headers(awsHeaders)
            .build()
    }

    private fun calculateAwsHeaders(request: Request): Headers {
        val date = getGmt0Date()
        val signInfo = getSignInfo(request, date)

        val authValue = getAuthValue(request, signInfo, date)

        val outHeadersList = mutableListOf<Pair<String, String>>().apply {
            addAll(signInfo.plainHeaders)
            addAll(signInfo.canonicalHeaders)
            add(AwsConstants.AUTH_HEADER_KEY to authValue)
        }

        val newHeadersBuilder = Headers.Builder()

        outHeadersList.forEach { (key, value) -> newHeadersBuilder.add(key, value) }

        return newHeadersBuilder.build()
    }

    private fun getSignInfo(request: Request, date: Date): SignInfo {
        val plainHeaders = mutableListOf<Pair<String, String>>()
        val xAmzHeaders = mutableListOf<Pair<String, String>>()

        var contentTypeHeader = Pair(
            AwsConstants.TRUE_CONTENT_TYPE_HEADER,
            AwsConstants.DEFAULT_CONTENT_TYPE_VALUE
        )

        request.headers.forEach { (key, value) ->
            when {
                key.contains(AwsConstants.X_AMZ_KEY_START, true) ->
                    xAmzHeaders.add(key to value)

                key.contains(AwsHeader.CONTENT_TYPE, true) ->
                    contentTypeHeader = Pair(AwsConstants.TRUE_CONTENT_TYPE_HEADER, value)

                else -> plainHeaders.add(key to value)
            }
        }

        plainHeaders.add(contentTypeHeader)
        plainHeaders.add(AwsConstants.HOST_HEADER to request.url.toUrl().host)

        ////
        val bodyBytes = request.bodyBytes()
        val contentMd5Value = Hash.MD5.calculate(bodyBytes).toBase64String()
        plainHeaders.add(AwsConstants.CONTENT_MD5_HEADER to contentMd5Value)

        plainHeaders.add(AwsConstants.DATE_HEADER to date.toRfc2822String())

        val sortedCanonicalHeadersWithoutCopies =
            normalizeCanonicalHeaders(xAmzHeaders)

        return SignInfo(
            plainHeaders = plainHeaders,
            canonicalHeaders = sortedCanonicalHeadersWithoutCopies,
            bodyHash = contentMd5Value
        )
    }

    private fun normalizeCanonicalHeaders(
        headers: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val sortedCanonicalHeaders = headers.sortedBy { (key, value) -> "$key:$value" }

        val sortedCanonicalWithoutCopies = mutableListOf<Pair<String, String>>()

        sortedCanonicalHeaders.forEachIndexed { index, pair ->
            if (index == 0) sortedCanonicalWithoutCopies.add(pair)
            else {
                val prevIndex = index - 1

                if (sortedCanonicalHeaders[prevIndex].first == pair.first) {
                    val prevHeader = sortedCanonicalWithoutCopies.first { it.first == pair.first }
                    val newPrevHeader = prevHeader.copy(
                        second = prevHeader.second + "," + pair.second
                    )

                    sortedCanonicalWithoutCopies[prevIndex] = newPrevHeader
                } else {
                    sortedCanonicalWithoutCopies.add(pair)
                }
            }
        }

        return sortedCanonicalWithoutCopies
    }

    private fun getAuthValue(request: Request, signInfo: SignInfo, date: Date): String {
        val signature = getSignature(
            request,
            signInfo,
            date
        )

        return String.format(
            AwsConstants.AUTH_V2_HEADER_VALUE_FORMAT,
            credentialsStore.accessKey,
            signature
        )
    }

    private fun getSignature(request: Request, signInfo: SignInfo, date: Date): String {
        if (request.method == "POST") {
            val errorMessage = "POST-requests authentication not implemented, use PUT-requests"
            throw IllegalStateException(errorMessage)
        }

        val stringToSign = getStringToSign(request, signInfo, date)

        val signature = HmacHash.SHA_1.calculate(
            credentialsStore.secretKey.toByteArray(),
            stringToSign.toByteArray()
        )
        return signature.toBase64String()
    }

    private fun getStringToSign(request: Request, signInfo: SignInfo, date: Date): String {
        val type = request.header(AwsHeader.CONTENT_TYPE)

        val stringToSignBuilder = StringBuilder()
            .appendLine(request.method)
            .appendLine(signInfo.bodyHash)
            .appendLine(type)
            .appendLine(date.toRfc2822String())

        val canonicalHeaderBuilder = StringBuilder()

        signInfo.canonicalHeaders.forEachIndexed { index, (key, value) ->
            canonicalHeaderBuilder.append("${key.lowercase()}:${value.lowercase()}")

            val isNotLastIndex = index != signInfo.canonicalHeaders.lastIndex
            if (isNotLastIndex) canonicalHeaderBuilder.appendLine()
        }

        val canonicalHeaderString = canonicalHeaderBuilder.toString()

        val resource = request.url.toUrl().path.replace(endpointPrefix, "")

        if (canonicalHeaderString.isNotBlank())
            stringToSignBuilder.appendLine(canonicalHeaderString)

        stringToSignBuilder.append(resource)

        return stringToSignBuilder.toString()
    }
}