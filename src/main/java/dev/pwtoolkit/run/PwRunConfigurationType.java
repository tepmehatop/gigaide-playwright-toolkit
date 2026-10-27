package dev.pwtoolkit.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PwRunConfigurationType extends ConfigurationTypeBase {
    public static final String ID = "PlaywrightToolkitTest";

    public PwRunConfigurationType() {
        super(ID, "Playwright Test", "Run or debug Playwright tests", AllIcons.RunConfigurations.TestState.Run);
        addFactory(new ConfigurationFactory(this) {
            @Override
            public @NotNull String getId() {
                return ID;
            }

            @Override
            public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
                return new PwRunConfiguration(project, this, "Playwright Test");
            }

            @Override
            public @Nullable Class<? extends BaseState> getOptionsClass() {
                return PwRunConfigurationOptions.class;
            }
        });
    }

    public static @NotNull PwRunConfigurationType getInstance() {
        return ConfigurationTypeUtil.findConfigurationType(PwRunConfigurationType.class);
    }

    public @NotNull ConfigurationFactory getFactory() {
        return getConfigurationFactories()[0];
    }
}
