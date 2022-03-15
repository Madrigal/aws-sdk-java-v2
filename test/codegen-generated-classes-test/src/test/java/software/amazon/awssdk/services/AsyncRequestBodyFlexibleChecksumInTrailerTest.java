/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.services;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static software.amazon.awssdk.http.Header.CONTENT_LENGTH;
import static software.amazon.awssdk.http.Header.CONTENT_TYPE;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.URI;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.HttpChecksumConstant;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.internal.util.Mimetype;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.protocolrestjson.ProtocolRestJsonAsyncClient;
import software.amazon.awssdk.services.protocolrestjson.model.ChecksumAlgorithm;
import software.amazon.awssdk.testutils.EnvironmentVariableHelper;

public class AsyncRequestBodyFlexibleChecksumInTrailerTest {
    private static final String CRLF = "\r\n";
    private static final EnvironmentVariableHelper ENVIRONMENT_VARIABLE_HELPER = new EnvironmentVariableHelper();
    private static final String SCENARIO = "scenario";
    private static final String PATH = "/";
    private static final String JSON_BODY = "{\"StringMember\":\"foo\"}";
    @Rule
    public WireMockRule wireMock = new WireMockRule(0);
    private ProtocolRestJsonAsyncClient asyncClient;
    private ProtocolRestJsonAsyncClient asyncClientWithSigner;

    @Before
    public void setupClient() {
        asyncClientWithSigner = ProtocolRestJsonAsyncClient.builder()
                                                           .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "skid")))
                                                           .region(Region.US_EAST_1)
                                                           .endpointOverride(URI.create("http://localhost:" + wireMock.port()))
                                                           .build();

        asyncClient = ProtocolRestJsonAsyncClient.builder()
                                                 .credentialsProvider(AnonymousCredentialsProvider.create())
                                                 .region(Region.US_EAST_1)
                                                 .endpointOverride(URI.create("http://localhost:" + wireMock.port()))
                                                 .build();
    }

    @After
    public void cleanUp() {
        ENVIRONMENT_VARIABLE_HELPER.reset();
    }

    @Test
    public void asyncStreaming_NoSigner_shouldContainChecksum_fromInterceptors() {
        stubResponseWithHeaders();
        asyncClient.putOperationWithChecksum(b -> b.checksumAlgorithm(ChecksumAlgorithm.CRC32), AsyncRequestBody.fromString(
            "abc"),
                                             AsyncResponseTransformer.toBytes()).join();
        //payload would in json form as  "{"StringMember":"foo"}x-amz-checksum-crc32:tcUDMQ==[\r][\n]"
        verify(putRequestedFor(anyUrl()).withHeader(CONTENT_LENGTH, equalTo("44")));
        verify(putRequestedFor(anyUrl()).withHeader(HttpChecksumConstant.HEADER_FOR_TRAILER_REFERENCE, equalTo("x-amz-checksum-crc32")));
        verify(putRequestedFor(anyUrl()).withHeader("x-amz-content-sha256", equalTo("STREAMING-UNSIGNED-PAYLOAD-TRAILER")));
        verify(putRequestedFor(anyUrl()).withHeader("x-amz-decoded-content-length", equalTo("3")));
        verify(putRequestedFor(anyUrl()).withHeader("content-encoding", equalTo("aws-chunked")));
        verify(putRequestedFor(anyUrl()).withHeader("Content-Encoding", equalTo("aws-chunked")));
        verify(putRequestedFor(anyUrl()).withRequestBody(
            containing(
                "3" + CRLF + "abc" + CRLF
                + "0" + CRLF
                + "x-amz-checksum-crc32:NSRBwg==" + CRLF + CRLF)));
    }

    @Test
    public void asyncStreaming_withRetry_NoSigner_shouldContainChecksum_fromInterceptors() {
        stubForFailureThenSuccess(500, "500");
        final String expectedRequestBody =
            "3" + CRLF + "abc" + CRLF
            + "0" + CRLF
            + "x-amz-checksum-sha256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=" + CRLF + CRLF;

        asyncClient.putOperationWithChecksum(b -> b.checksumAlgorithm(ChecksumAlgorithm.SHA256), AsyncRequestBody.fromString(
            "abc"),
                                             AsyncResponseTransformer.toBytes()).join();
        List<LoggedRequest> requests = getRecordedRequests();
        assertThat(requests.size()).isEqualTo(2);
        assertThat(requests.get(0).getBody()).contains(expectedRequestBody.getBytes());
        assertThat(requests.get(1).getBody()).contains(expectedRequestBody.getBytes());
        verify(putRequestedFor(anyUrl()).withHeader(CONTENT_TYPE, equalTo(Mimetype.MIMETYPE_TEXT_PLAIN)));
        verify(putRequestedFor(anyUrl()).withHeader(CONTENT_LENGTH, equalTo("81")));
        verify(putRequestedFor(anyUrl()).withHeader(HttpChecksumConstant.HEADER_FOR_TRAILER_REFERENCE, equalTo("x-amz-checksum-sha256")));
        verify(putRequestedFor(anyUrl()).withHeader("x-amz-content-sha256", equalTo("STREAMING-UNSIGNED-PAYLOAD-TRAILER")));
        verify(putRequestedFor(anyUrl()).withHeader("x-amz-decoded-content-length", equalTo("3")));
        verify(putRequestedFor(anyUrl()).withHeader("Content-Encoding", equalTo("aws-chunked")));
        verify(putRequestedFor(anyUrl()).withRequestBody(
            containing(
                expectedRequestBody)));
    }

    private void stubResponseWithHeaders() {
        stubFor(put(anyUrl())
                    .willReturn(aResponse().withStatus(200)
                                           .withHeader("x-foo-id", "foo")
                                           .withHeader("x-bar-id", "bar")
                                           .withHeader("x-foobar-id", "foobar")
                                           .withBody("{}")));
    }

    private void stubForFailureThenSuccess(int statusCode, String errorCode) {
        WireMock.reset();
        stubFor(put(urlEqualTo(PATH))
                    .inScenario(SCENARIO)
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willSetStateTo("1")
                    .willReturn(aResponse()
                                    .withStatus(statusCode)
                                    .withHeader("x-amzn-ErrorType", errorCode)
                                    .withBody("{}")));

        stubFor(put(urlEqualTo(PATH))
                    .inScenario(SCENARIO)
                    .whenScenarioStateIs("1")
                    .willSetStateTo("2")
                    .willReturn(aResponse()
                                    .withStatus(200)
                                    .withBody(JSON_BODY)));
    }


    private List<LoggedRequest> getRecordedRequests() {
        return findAll(putRequestedFor(urlEqualTo(PATH)));
    }


}