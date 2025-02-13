package ee.carlrobert.codegpt.completions;

import static java.util.stream.Collectors.toList;

import com.intellij.openapi.diagnostic.Logger;
import ee.carlrobert.codegpt.CodeGPTPlugin;
import ee.carlrobert.codegpt.EncodingManager;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.ConversationsState;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.settings.configuration.ConfigurationState;
import ee.carlrobert.codegpt.settings.state.SettingsState;
import ee.carlrobert.codegpt.settings.state.YouSettingsState;
import ee.carlrobert.embedding.EmbeddingsService;
import ee.carlrobert.llm.client.openai.completion.chat.OpenAIChatCompletionModel;
import ee.carlrobert.llm.client.openai.completion.chat.request.OpenAIChatCompletionMessage;
import ee.carlrobert.llm.client.openai.completion.chat.request.OpenAIChatCompletionRequest;
import ee.carlrobert.llm.client.you.completion.YouCompletionRequest;
import ee.carlrobert.llm.client.you.completion.YouCompletionRequestMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public class CompletionRequestProvider {

  private static final Logger LOG = Logger.getInstance(CompletionRequestProvider.class);

  public static final String COMPLETION_SYSTEM_PROMPT = "You are an AI programming assistant.\n" +
      "When asked for you name, you must respond with \"CodeGPT\".\n" +
      "Follow the user's requirements carefully & to the letter.\n" +
      "Your responses should be informative and logical.\n" +
      "You should always adhere to technical information.\n" +
      "If the user asks for code or technical questions, you must provide code suggestions and adhere to technical information.\n" +
      "If the question is related to a developer, CodeGPT must respond with content related to a developer.\n" +
      "First think step-by-step - describe your plan for what to build in pseudocode, written out in great detail.\n" +
      "Then output the code in a single code block.\n" +
      "Minimize any other prose.\n" +
      "Keep your answers short and impersonal.\n" +
      "Use Markdown formatting in your answers.\n" +
      "Make sure to include the programming language name at the start of the Markdown code blocks.\n" +
      "Avoid wrapping the whole response in triple backticks.\n" +
      "The user works in an IDE built by JetBrains which has a concept for editors with open files, integrated unit test support, " +
      "and output pane that shows the output of running the code as well as an integrated terminal.\n" +
      "You can only give one reply for each conversation turn.";

  private final EncodingManager encodingManager = EncodingManager.getInstance();
  private final EmbeddingsService embeddingsService;
  private final Conversation conversation;

  public CompletionRequestProvider(Conversation conversation) {
    this.embeddingsService = new EmbeddingsService(CompletionClientProvider.getOpenAIClient(), CodeGPTPlugin.getPluginBasePath());
    this.conversation = conversation;
  }

  public YouCompletionRequest buildYouCompletionRequest(Message message) {
    return new YouCompletionRequest.Builder(message.getPrompt())
        .setUseGPT4Model(YouSettingsState.getInstance().isUseGPT4Model())
        .setChatHistory(conversation.getMessages().stream()
            .map(prevMessage -> new YouCompletionRequestMessage(prevMessage.getPrompt(), prevMessage.getResponse()))
            .collect(toList()))
        .build();
  }

  public OpenAIChatCompletionRequest buildOpenAIChatCompletionRequest(String model, Message message, boolean isRetry) {
    return buildOpenAIChatCompletionRequest(model, message, isRetry, false, null);
  }

  public OpenAIChatCompletionRequest buildOpenAIChatCompletionRequest(
      String model,
      Message message,
      boolean isRetry,
      boolean useContextualSearch,
      @Nullable String overriddenPath) {
    var builder = new OpenAIChatCompletionRequest.Builder(buildMessages(model, message, isRetry, useContextualSearch))
        .setModel(model)
        .setMaxTokens(ConfigurationState.getInstance().getMaxTokens())
        .setTemperature(ConfigurationState.getInstance().getTemperature());

    if (overriddenPath != null) {
      builder.setOverriddenPath(overriddenPath);
    }

    return (OpenAIChatCompletionRequest) builder.build();
  }

  private List<OpenAIChatCompletionMessage> buildMessages(String model, Message message, boolean isRetry, boolean useContextualSearch) {
    var messages = new ArrayList<OpenAIChatCompletionMessage>();
    if (useContextualSearch) {
      var prompt = embeddingsService.buildPromptWithContext(message.getPrompt());
      LOG.info("Retrieved context:\n" + prompt);
      messages.add(new OpenAIChatCompletionMessage("user", prompt));
    } else {
      var systemPrompt = ConfigurationState.getInstance().getSystemPrompt();
      messages.add(new OpenAIChatCompletionMessage("system",
          systemPrompt.isEmpty() ? COMPLETION_SYSTEM_PROMPT : systemPrompt));

      for (var prevMessage : conversation.getMessages()) {
        if (isRetry && prevMessage.getId().equals(message.getId())) {
          break;
        }
        messages.add(new OpenAIChatCompletionMessage("user", prevMessage.getPrompt()));
        messages.add(new OpenAIChatCompletionMessage("assistant", prevMessage.getResponse()));
      }
      messages.add(new OpenAIChatCompletionMessage("user", message.getPrompt()));
    }

    if (SettingsState.getInstance().isUseYouService()) {
      return messages;
    }

    int totalUsage = messages.parallelStream()
        .mapToInt(encodingManager::countMessageTokens)
        .sum() + ConfigurationState.getInstance().getMaxTokens();
    int modelMaxTokens;
    try {
      modelMaxTokens = OpenAIChatCompletionModel.findByCode(model).getMaxTokens();

      if (totalUsage <= modelMaxTokens) {
        return messages;
      }
    } catch (NoSuchElementException ex) {
      return messages;
    }
    return tryReducingMessagesOrThrow(messages, totalUsage, modelMaxTokens);
  }

  private List<OpenAIChatCompletionMessage> tryReducingMessagesOrThrow(
      List<OpenAIChatCompletionMessage> messages,
      int totalUsage,
      int modelMaxTokens) {
    if (!ConversationsState.getInstance().discardAllTokenLimits) {
      if (!conversation.isDiscardTokenLimit()) {
        throw new TotalUsageExceededException();
      }
    }

    // skip the system prompt
    for (int i = 1; i < messages.size(); i++) {
      if (totalUsage <= modelMaxTokens) {
        break;
      }

      totalUsage -= encodingManager.countMessageTokens(messages.get(i));
      messages.set(i, null);
    }

    return messages.stream().filter(Objects::nonNull).collect(toList());
  }
}
