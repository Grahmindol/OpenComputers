package li.cil.oc.common.openprinter.printer;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class PrinterClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLE_CUSTOM_MODEL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("printer");
        ENABLE_CUSTOM_MODEL = builder.comment("Use the detailed OpenPrinter model; disable to render a simple cube.")
                .define("enableCustomModel", false);
        builder.pop();
        SPEC = builder.build();
    }

    private PrinterClientConfig() {}
}
