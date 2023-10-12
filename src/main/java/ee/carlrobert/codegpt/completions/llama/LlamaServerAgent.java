package ee.carlrobert.codegpt.completions.llama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import ee.carlrobert.codegpt.CodeGPTPlugin;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

public class LlamaServerAgent {

  private static final Logger LOG = Logger.getInstance(LlamaServerAgent.class);

  private final String modelPath;

  public LlamaServerAgent(String modelPath) {
    this.modelPath = modelPath;
  }

  public void startAgent(Runnable onSuccess) {
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        var process = new OSProcessHandler(getMakeCommandLinde());
        process.addProcessListener(getMakeProcessListener(onSuccess));

        LOG.info("Building llama.cpp");
        process.startNotify();
      } catch (ExecutionException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private ProcessListener getMakeProcessListener(Runnable onSuccess) {
    return new ProcessAdapter() {
      @Override
      public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        LOG.info(event.getText());
      }

      @Override
      public void processTerminated(@NotNull ProcessEvent event) {
        ProcessHandler processHandler;
        try {
          processHandler = new OSProcessHandler(getServerCommandLine());
        } catch (ExecutionException e) {
          throw new RuntimeException(e);
        }
        processHandler.addProcessListener(getProcessListener(onSuccess));

        LOG.info("Starting server");
        processHandler.startNotify();
      }
    };
  }

  private ProcessListener getProcessListener(Runnable onSuccess) {
    return new ProcessAdapter() {
      private final ObjectMapper objectMapper = new ObjectMapper();

      @Override
      public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        System.out.println(event.getText());

        if (outputType == ProcessOutputType.STDOUT) {
          try {
            var serverMessage = objectMapper.readValue(event.getText(), LlamaServerMessage.class);
            if ("HTTP server listening".equals(serverMessage.getMessage())) {
              onSuccess.run();
            }
          } catch (Exception ignore) {
          }
        }
      }
    };
  }

  private static GeneralCommandLine getMakeCommandLinde() {
    GeneralCommandLine commandLine = new GeneralCommandLine().withCharset(StandardCharsets.UTF_8);
    commandLine.setExePath("make");
    commandLine.withWorkDirectory(CodeGPTPlugin.getLlamaSourcePath());
    commandLine.addParameters("-j");
    commandLine.setRedirectErrorStream(false);
    return commandLine;
  }

  private GeneralCommandLine getServerCommandLine() {
    GeneralCommandLine commandLine = new GeneralCommandLine().withCharset(StandardCharsets.UTF_8);
    commandLine.setExePath("./server");
    commandLine.withWorkDirectory(CodeGPTPlugin.getLlamaSourcePath());
    commandLine.addParameters("-m", modelPath, "-c", "2048");
    commandLine.setRedirectErrorStream(false);
    return commandLine;
  }
}
