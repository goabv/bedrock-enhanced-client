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

package software.amazon.awssdk.enhanced.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseMetrics;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

@ExtendWith(MockitoExtension.class)
class BasicConversationTest {

    private static final String MODEL_ID = "anthropic.claude-3-sonnet-20240229-v1:0";

    @Mock
    private BedrockRuntimeClient mockBedrockClient;

    private BedrockEnhancedClient enhancedClient;

    @BeforeEach
    void setUp() {
        enhancedClient = BedrockEnhancedClient.builder()
                                              .bedrockRuntimeClient(mockBedrockClient)
                                              .retryConfig(r -> r.maxRetries(0))
                                              .build();
    }

    @Test
    void singleTurnConversation_sendsMessageAndReturnsResponse() {
        ConverseResponse mockResponse = buildMockResponse("Hello! I'm doing well.", 10, 15);
        when(mockBedrockClient.converse(any(ConverseRequest.class))).thenReturn(mockResponse);

        ChatSession session = enhancedClient.createSession(MODEL_ID);
        ChatResponse response = session.converse("Hello, how are you?");

        assertThat(response.text()).isEqualTo("Hello! I'm doing well.");
        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.turnTokenUsage().inputTokens()).isEqualTo(10);
        assertThat(response.turnTokenUsage().outputTokens()).isEqualTo(15);
        assertThat(response.conversationMessageCount()).isEqualTo(2);

        // Verify the request sent to Bedrock
        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(mockBedrockClient).converse(captor.capture());

        ConverseRequest sentRequest = captor.getValue();
        assertThat(sentRequest.modelId()).isEqualTo(MODEL_ID);
        assertThat(sentRequest.messages()).hasSize(1);
        assertThat(sentRequest.messages().get(0).role()).isEqualTo(ConversationRole.USER);
    }

    @Test
    void multiTurnConversation_maintainsHistory() {
        ConverseResponse firstResponse = buildMockResponse("I'm Claude.", 10, 8);
        ConverseResponse secondResponse = buildMockResponse("Java is a programming language.", 25, 20);
        when(mockBedrockClient.converse(any(ConverseRequest.class)))
            .thenReturn(firstResponse)
            .thenReturn(secondResponse);

        ChatSession session = enhancedClient.createSession(MODEL_ID);

        ChatResponse first = session.converse("Who are you?");
        assertThat(first.text()).isEqualTo("I'm Claude.");
        assertThat(first.conversationMessageCount()).isEqualTo(2);

        ChatResponse second = session.converse("Tell me about Java.");
        assertThat(second.text()).isEqualTo("Java is a programming language.");
        assertThat(second.conversationMessageCount()).isEqualTo(4);

        // Verify second request includes full conversation history
        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(mockBedrockClient, times(2)).converse(captor.capture());

        List<ConverseRequest> requests = captor.getAllValues();
        ConverseRequest secondRequest = requests.get(1);
        assertThat(secondRequest.messages()).hasSize(3);
        assertThat(secondRequest.messages().get(0).role()).isEqualTo(ConversationRole.USER);
        assertThat(secondRequest.messages().get(1).role()).isEqualTo(ConversationRole.ASSISTANT);
        assertThat(secondRequest.messages().get(2).role()).isEqualTo(ConversationRole.USER);
    }

    @Test
    void sessionWithSystemPrompt_includesSystemInRequest() {
        ConverseResponse mockResponse = buildMockResponse("Bonjour!", 12, 5);
        when(mockBedrockClient.converse(any(ConverseRequest.class))).thenReturn(mockResponse);

        ChatSession session = enhancedClient.createSession(
            CreateSessionRequest.builder()
                                .modelId(MODEL_ID)
                                .addSystemPrompt(SystemContentBlock.fromText("You are a French translator."))
                                .inferenceConfig(InferenceConfiguration.builder()
                                                                       .maxTokens(1024)
                                                                       .temperature(0.7f)
                                                                       .build())
                                .build());

        session.converse("Hello");

        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(mockBedrockClient).converse(captor.capture());

        ConverseRequest sentRequest = captor.getValue();
        assertThat(sentRequest.system()).isNotEmpty();
        assertThat(sentRequest.inferenceConfig().maxTokens()).isEqualTo(1024);
        assertThat(sentRequest.inferenceConfig().temperature()).isEqualTo(0.7f);
    }

    @Test
    void tokenUsageTracking_accumulatesAcrossTurns() {
        ConverseResponse first = buildMockResponse("Response 1", 10, 15);
        ConverseResponse second = buildMockResponse("Response 2", 30, 20);
        when(mockBedrockClient.converse(any(ConverseRequest.class)))
            .thenReturn(first)
            .thenReturn(second);

        ChatSession session = enhancedClient.createSession(MODEL_ID);
        session.converse("Message 1");
        session.converse("Message 2");

        TokenUsageSummary usage = session.tokenUsage();
        assertThat(usage.totalInputTokens()).isEqualTo(40);
        assertThat(usage.totalOutputTokens()).isEqualTo(35);
        assertThat(usage.totalTokens()).isEqualTo(75);
        assertThat(usage.turnCount()).isEqualTo(2);
    }

    @Test
    void tokenBudget_throwsWhenExceeded() {
        ConverseResponse mockResponse = buildMockResponse("Big response", 50, 60);
        when(mockBedrockClient.converse(any(ConverseRequest.class))).thenReturn(mockResponse);

        ChatSession session = enhancedClient.createSession(
            CreateSessionRequest.builder()
                                .modelId(MODEL_ID)
                                .tokenBudget(100)
                                .build());

        // First call uses 110 tokens (50 + 60), which is under budget at call time (0 < 100)
        session.converse("First message");

        // Second call should fail because cumulative usage (110) >= budget (100)
        assertThatThrownBy(() -> session.converse("Second message"))
            .isInstanceOf(TokenBudgetExceededException.class)
            .satisfies(ex -> {
                TokenBudgetExceededException budgetEx = (TokenBudgetExceededException) ex;
                assertThat(budgetEx.currentUsage()).isEqualTo(110);
                assertThat(budgetEx.budget()).isEqualTo(100);
            });
    }

    @Test
    void sessionReset_clearsHistoryAndTokens() {
        ConverseResponse mockResponse = buildMockResponse("Hello", 10, 5);
        when(mockBedrockClient.converse(any(ConverseRequest.class))).thenReturn(mockResponse);

        ChatSession session = enhancedClient.createSession(MODEL_ID);
        session.converse("Hi");

        assertThat(session.conversationHistory()).hasSize(2);
        assertThat(session.tokenUsage().totalTokens()).isEqualTo(15);

        session.reset();

        assertThat(session.conversationHistory()).isEmpty();
        assertThat(session.tokenUsage().totalTokens()).isEqualTo(0);
        assertThat(session.tokenUsage().turnCount()).isEqualTo(0);
    }

    @Test
    void converseWithContentBlocks_sendsStructuredContent() {
        ConverseResponse mockResponse = buildMockResponse("I see the text.", 20, 10);
        when(mockBedrockClient.converse(any(ConverseRequest.class))).thenReturn(mockResponse);

        ChatSession session = enhancedClient.createSession(MODEL_ID);
        List<ContentBlock> content = Collections.singletonList(ContentBlock.fromText("Analyze this text."));
        ChatResponse response = session.converse(content);

        assertThat(response.text()).isEqualTo("I see the text.");

        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(mockBedrockClient).converse(captor.capture());
        assertThat(captor.getValue().messages().get(0).content().get(0).text()).isEqualTo("Analyze this text.");
    }

    @Test
    void converseWithRequestOverride_allowsCustomization() {
        ConverseResponse mockResponse = buildMockResponse("Tool result", 15, 12);
        when(mockBedrockClient.converse(any(ConverseRequest.class))).thenReturn(mockResponse);

        ChatSession session = enhancedClient.createSession(MODEL_ID);
        ChatResponse response = session.converse(builder ->
            builder.requestMetadata(Collections.singletonMap("traceId", "abc-123"))
        );

        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(mockBedrockClient).converse(captor.capture());

        ConverseRequest sentRequest = captor.getValue();
        assertThat(sentRequest.modelId()).isEqualTo(MODEL_ID);
        assertThat(sentRequest.requestMetadata()).containsEntry("traceId", "abc-123");
    }

    @Test
    void emptyResponseText_returnsEmptyString() {
        ConverseResponse emptyResponse = ConverseResponse.builder()
            .output(ConverseOutput.fromMessage(Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(Collections.emptyList())
                .build()))
            .stopReason(StopReason.END_TURN)
            .usage(TokenUsage.builder().inputTokens(5).outputTokens(0).totalTokens(5).build())
            .metrics(ConverseMetrics.builder().latencyMs(50L).build())
            .build();
        when(mockBedrockClient.converse(any(ConverseRequest.class))).thenReturn(emptyResponse);

        ChatSession session = enhancedClient.createSession(MODEL_ID);
        ChatResponse response = session.converse("Hello");

        assertThat(response.text()).isEmpty();
    }

    private static ConverseResponse buildMockResponse(String text, int inputTokens, int outputTokens) {
        return ConverseResponse.builder()
                               .output(ConverseOutput.fromMessage(
                                   Message.builder()
                                          .role(ConversationRole.ASSISTANT)
                                          .content(ContentBlock.fromText(text))
                                          .build()))
                               .stopReason(StopReason.END_TURN)
                               .usage(TokenUsage.builder()
                                                .inputTokens(inputTokens)
                                                .outputTokens(outputTokens)
                                                .totalTokens(inputTokens + outputTokens)
                                                .build())
                               .metrics(ConverseMetrics.builder()
                                                       .latencyMs(100L)
                                                       .build())
                               .build();
    }
}
