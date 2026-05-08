/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.component.ai.agent.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.bytechef.component.ai.agent.facade.AiAgentToolFacade;
import com.bytechef.component.ai.llm.util.ModelUtils;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ai.agent.ChatMemoryFunction;
import com.bytechef.platform.component.definition.ai.agent.ModelFunction;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElementMap;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class AbstractAiAgentChatActionTest {

    @Mock
    private AiAgentToolFacade aiAgentToolFacade;

    @Mock
    private ClusterElementDefinitionService clusterElementDefinitionService;

    @Mock
    private ToolCallingManager toolCallingManager;

    @Test
    void testGetChatClientRequestSpecWithNullParameterValues() throws Exception {
        HashMap<String, Object> inputParamsMap = new HashMap<>();

        inputParamsMap.put("key1", "value1");
        inputParamsMap.put("nullableKey", null);

        Parameters inputParameters = MockParametersFactory.create(inputParamsMap);

        HashMap<String, Object> clusterElementParams = new HashMap<>();

        clusterElementParams.put("model", "gpt-4o");
        clusterElementParams.put("nullableParam", null);

        Map<String, Object> modelElement = new HashMap<>();

        modelElement.put("name", "model_1");
        modelElement.put("type", "testComponent/v1/testModel");
        modelElement.put("parameters", clusterElementParams);

        Parameters extensions = MockParametersFactory.create(
            Map.of("clusterElements", Map.of("model", modelElement)));

        ModelFunction modelFunction = mock(ModelFunction.class);

        ChatModel chatModel = mock(ChatModel.class);

        when(modelFunction.apply(any(), any(), anyBoolean())).thenAnswer(invocation -> chatModel);
        when(clusterElementDefinitionService.<ModelFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testModel"))).thenReturn(modelFunction);

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);

        Map<String, ComponentConnection> connectionParameters = Map.of("model_1", componentConnection);

        ActionContext actionContext = mock(ActionContext.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            assertDoesNotThrow(() -> action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext));
        }
    }

    @Test
    void testGetAdvisorsIncludesToolCallAdvisorWithDefaultConversationHistoryWhenNoChatMemory() {
        ClusterElementMap clusterElementMap = ClusterElementMap.of(
            Map.of("clusterElements", Map.of("model", buildModelClusterElement())));

        ActionContext actionContext = mock(ActionContext.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        List<Advisor> advisors = action.getAdvisors(clusterElementMap, Map.of(), actionContext);

        ToolCallAdvisor toolCallAdvisor = findToolCallAdvisor(advisors);

        assertThat(toolCallAdvisor).isNotNull();
        assertThat(advisors).noneMatch(BaseChatMemoryAdvisor.class::isInstance);
        assertThat(readConversationHistoryEnabled(toolCallAdvisor)).isTrue();
    }

    @Test
    void testGetAdvisorsAddsChatMemoryBeforeToolCallAdvisorAndDisablesInternalConversationHistory() throws Exception {
        Map<String, Object> chatMemoryElement = new HashMap<>();

        chatMemoryElement.put("name", "memory_1");
        chatMemoryElement.put("type", "memoryComponent/v1/memoryElement");
        chatMemoryElement.put("parameters", Map.of());

        ClusterElementMap clusterElementMap = ClusterElementMap.of(
            Map.of(
                "clusterElements",
                Map.of("model", buildModelClusterElement(), "chatMemory", chatMemoryElement)));

        BaseChatMemoryAdvisor chatMemoryAdvisor = mock(BaseChatMemoryAdvisor.class);

        ChatMemoryFunction chatMemoryFunction = mock(ChatMemoryFunction.class);

        when(chatMemoryFunction.apply(any(), any(), any(), any())).thenReturn(chatMemoryAdvisor);
        when(clusterElementDefinitionService.<ChatMemoryFunction>getClusterElement(
            eq("memoryComponent"), eq(1), eq("memoryElement"))).thenReturn(chatMemoryFunction);

        ComponentConnection memoryConnection = new ComponentConnection(
            "memoryComponent", 1, 2L, Map.of(), null);

        Map<String, ComponentConnection> connectionParameters = Map.of("memory_1", memoryConnection);
        ActionContext actionContext = mock(ActionContext.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        List<Advisor> advisors = action.getAdvisors(clusterElementMap, connectionParameters, actionContext);

        int chatMemoryIndex = advisors.indexOf(chatMemoryAdvisor);
        ToolCallAdvisor toolCallAdvisor = findToolCallAdvisor(advisors);
        int toolCallIndex = advisors.indexOf(toolCallAdvisor);

        assertThat(chatMemoryIndex).isGreaterThanOrEqualTo(0);
        assertThat(toolCallIndex).isGreaterThan(chatMemoryIndex);
        assertThat(readConversationHistoryEnabled(toolCallAdvisor)).isFalse();
    }

    private static Map<String, Object> buildModelClusterElement() {
        Map<String, Object> modelElement = new HashMap<>();

        modelElement.put("name", "model_1");
        modelElement.put("type", "testComponent/v1/testModel");
        modelElement.put("parameters", Map.of());

        return modelElement;
    }

    private static ToolCallAdvisor findToolCallAdvisor(List<Advisor> advisors) {
        return advisors.stream()
            .filter(ToolCallAdvisor.class::isInstance)
            .map(ToolCallAdvisor.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected ToolCallAdvisor in advisor list"));
    }

    private static boolean readConversationHistoryEnabled(ToolCallAdvisor toolCallAdvisor) {
        try {
            Field field = ToolCallAdvisor.class.getDeclaredField("conversationHistoryEnabled");

            field.setAccessible(true);

            return field.getBoolean(toolCallAdvisor);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                "Unable to read conversationHistoryEnabled from ToolCallAdvisor — "
                    + "field name changed in Spring AI?",
                exception);
        }
    }

    private static class TestAiAgentChatAction extends AbstractAiAgentChatAction {

        TestAiAgentChatAction(
            AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService,
            ToolCallingManager toolCallingManager) {

            super(aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);
        }
    }
}
