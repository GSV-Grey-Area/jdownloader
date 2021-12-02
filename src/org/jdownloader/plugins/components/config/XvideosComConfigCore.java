package org.jdownloader.plugins.components.config;

import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.appwork.storage.config.annotations.DefaultEnumValue;
import org.appwork.storage.config.annotations.DescriptionForConfigEntry;
import org.appwork.storage.config.annotations.LabelInterface;
import org.jdownloader.plugins.config.Order;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.TakeValueFromSubconfig;

public interface XvideosComConfigCore extends PluginConfigInterface {
    @AboutConfig
    @DefaultBooleanValue(false)
    @DescriptionForConfigEntry("Enable fast linkcheck for host plugin? If enabled, filesize won't be displayed until download is started!")
    @Order(15)
    boolean isEnableFastLinkcheckForHostPlugin();

    void setEnableFastLinkcheckForHostPlugin(boolean b);

    @AboutConfig
    @DefaultBooleanValue(true)
    @TakeValueFromSubconfig("Prefer HLS")
    @DescriptionForConfigEntry("Prefer HLS download?")
    @Order(30)
    boolean isPreferHLSStreamDownload();

    void setPreferHLSStreamDownload(boolean b);

    public static enum PreferredHLSQuality implements LabelInterface {
        Q2160P {
            @Override
            public String getLabel() {
                return "2160p (4k)";
            }
        },
        Q1080P {
            @Override
            public String getLabel() {
                return "1080p";
            }
        },
        Q720P {
            @Override
            public String getLabel() {
                return "720p";
            }
        },
        Q480P {
            @Override
            public String getLabel() {
                return "480p";
            }
        },
        Q360P {
            @Override
            public String getLabel() {
                return "360p";
            }
        };
    }

    public static enum PreferredHTTPQuality implements LabelInterface {
        HIGH {
            @Override
            public String getLabel() {
                return "High quality";
            }
        },
        LOW {
            @Override
            public String getLabel() {
                return "Low quality";
            }
        };
    }

    public static enum PreferredOfficialDownloadQuality implements LabelInterface {
        Q2160P {
            @Override
            public String getLabel() {
                return "2160p (4k)";
            }
        },
        Q1080P {
            @Override
            public String getLabel() {
                return "1080p";
            }
        },
        Q360P {
            @Override
            public String getLabel() {
                return "360p";
            }
        },
        Q240P {
            @Override
            public String getLabel() {
                return "240p";
            }
        };
    }

    @AboutConfig
    @DefaultEnumValue("Q2160P")
    @DescriptionForConfigEntry("Select preferred HLS download quality. If your preferred HLS quality is not found, best quality will be downloaded instead.")
    @Order(100)
    PreferredHLSQuality getPreferredHLSQuality();

    void setPreferredHLSQuality(PreferredHLSQuality quality);

    @AboutConfig
    @DefaultEnumValue("HIGH")
    @DescriptionForConfigEntry("Select preferred HTTP download quality. If your preferred HTTP quality is not found, best quality will be downloaded instead.")
    @Order(120)
    PreferredHTTPQuality getPreferredHTTPQuality();

    void setPreferredHTTPQuality(PreferredHTTPQuality quality);

    @AboutConfig
    @DefaultEnumValue("Q2160P")
    @DescriptionForConfigEntry("Select preferred official download quality ('download' button). If your preferred quality is not found, best quality will be downloaded instead.")
    @Order(130)
    PreferredOfficialDownloadQuality getPreferredOfficialDownloadQuality();

    void setPreferredOfficialDownloadQuality(PreferredOfficialDownloadQuality quality);

    @DefaultBooleanValue(false)
    @DescriptionForConfigEntry("xvideos.com can 'shadow ban' users who download a lot. This will limit the max. available quality to 240p. This experimental setting will make JD try to detect this limit.")
    @Order(140)
    boolean isTryToRecognizeLimit();

    void setTryToRecognizeLimit(boolean b);
}