package com.aharou.feature.backup.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AWS Signature V4 官方测试向量（aws-sig-v4-test-suite）回归。
 * 凭据与服务名取测试套件固定值（service），与生产使用的 s3 区分开——[S3SigV4.sign] 的 service 可传参。
 */
class S3SigV4Test {

    private val accessKey = "AKIDEXAMPLE"
    private val secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    private val region = "us-east-1"
    private val service = "service"
    private val amzDate = "20150830T123600Z"

    @Test
    fun sign_getVanilla_matchesAwsTestVector() {
        val authorization = S3SigV4.sign(
            accessKey = accessKey,
            secretKey = secretKey,
            region = region,
            method = "GET",
            canonicalUri = "/",
            canonicalQuery = "",
            headers = linkedMapOf("host" to "example.amazonaws.com", "x-amz-date" to amzDate),
            payloadHash = S3SigV4.sha256Hex(ByteArray(0)),
            amzDate = amzDate,
            service = service
        )

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            authorization
        )
    }

    @Test
    fun sign_getVanillaQueryOrderKeyCase_matchesAwsTestVector() {
        val authorization = S3SigV4.sign(
            accessKey = accessKey,
            secretKey = secretKey,
            region = region,
            method = "GET",
            canonicalUri = "/",
            canonicalQuery = "Param1=value1&Param2=value2",
            headers = linkedMapOf("host" to "example.amazonaws.com", "x-amz-date" to amzDate),
            payloadHash = S3SigV4.sha256Hex(ByteArray(0)),
            amzDate = amzDate,
            service = service
        )

        assertTrue(
            authorization.endsWith(
                "Signature=b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500"
            )
        )
    }

    @Test
    fun signingKey_matchesAwsTestVector() {
        val expected = "938127b5336810ddb6a5d6af445fcac9e371f9ed418ed386b022aed82901be75"
        assertEquals(expected, S3SigV4.hex(S3SigV4.signingKey(secretKey, "20150830", "us-east-1", service)))
    }

    @Test
    fun sha256Hex_emptyAndKnown() {
        assertEquals(S3SigV4.EMPTY_SHA256, S3SigV4.sha256Hex(ByteArray(0)))
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            S3SigV4.sha256Hex("hello".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun uriEncode_reservedAndUnreserved() {
        assertEquals("%2F", S3SigV4.uriEncode("/", encodeSlash = true))
        assertEquals("/a/b", S3SigV4.uriEncode("/a/b", encodeSlash = false))
        assertEquals("a%20b", S3SigV4.uriEncode("a b", encodeSlash = true))
        assertEquals("a~b_c-d.e", S3SigV4.uriEncode("a~b_c-d.e", encodeSlash = true))
        assertEquals("aharou%2Fbackups%2F", S3SigV4.uriEncode("aharou/backups/", encodeSlash = true))
    }
}
