package org.jdownloader.plugins.components.config;

import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.appwork.storage.config.annotations.DescriptionForConfigEntry;
import org.jdownloader.plugins.config.Order;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginHost;
import org.jdownloader.plugins.config.Type;

@PluginHost(host = "dropbox.com", type = Type.HOSTER)
public interface DropBoxConfig extends PluginConfigInterface {
    /** 2019-09-25: Enforced API usage for all users. Consider removing this setting soon. */
    public static class TRANSLATION {
        public String getUseAPI_label() {
            return "BETA: Use API (in account mode)?";
            // return "BETA: Use API (in account mode)? [Recommended, changing this setting does not have any effect at the moment!]";
            // return "Use API (in account mode)? [Recommended]";
        }

        public String getAskIfSubfoldersShouldBeCrawled_label() {
            return "If enabled, a dialog will show up whenever you add folders containing subfolders.";
        }
    }

    public static final TRANSLATION TRANSLATION = new TRANSLATION();

    @DefaultBooleanValue(false)
    @AboutConfig
    @DescriptionForConfigEntry("If enabled, API will be used")
    @Order(10)
    boolean isUseAPI();

    void setUseAPI(boolean b);

    @DefaultBooleanValue(false)
    @AboutConfig
    @DescriptionForConfigEntry("If enabled, a dialog will show up whenever you add folders containing subfolders.")
    @Order(20)
    boolean isAskIfSubfoldersShouldBeCrawled();

    void setAskIfSubfoldersShouldBeCrawled(boolean b);
}