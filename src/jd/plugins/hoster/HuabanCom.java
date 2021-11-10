//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;
import java.util.Map;

import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "huaban.com" }, urls = { "https?://(?:www\\.)?huaban\\.com/pins/(\\d+)" })
public class HuabanCom extends PluginForHost {
    public HuabanCom(PluginWrapper wrapper) {
        super(wrapper);
        setConfigElements();
    }

    @Override
    public String getAGBLink() {
        return "http://huaban.com/";
    }

    /* Site constants */
    public static final String default_extension = ".jpg";
    /* don't touch the following! */
    private String             dllink            = null;

    @SuppressWarnings({ "deprecation" })
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        String filename = null;
        final String pin_id = new Regex(link.getDownloadURL(), "(\\d+)$").getMatch(0);
        /* Display ids for offline links */
        link.setName(pin_id);
        link.setLinkID(pin_id);
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        String site_title = null;
        dllink = checkDirectLink(link, "free_directlink");
        if (dllink != null) {
            /* Avoid unnecessary site requests. */
            site_title = link.getFinalFileName();
            if (site_title == null) {
                site_title = pin_id;
            }
        } else {
            // final String source_url = link.getStringProperty("source_url", null);
            // final String boardid = link.getStringProperty("boardid", null);
            // final String username = link.getStringProperty("username", null);
            br.getPage(link.getDownloadURL());
            if (this.br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            /*
             * Site actually contains similar json compared to API --> Grab that and get the final link via that as it is not always present
             * in the normal html code.
             */
            String json = br.getRegex("app\\.page\\[\"pin\"\\] = (\\{.*?\\});\\s+").getMatch(0);
            if (json == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(json);
            dllink = getDirectlinkFromJson(entries);
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            link.setProperty("free_directlink", dllink);
        }
        final String ext = getFileNameExtensionFromString(dllink, default_extension);
        filename = pin_id;
        if (!filename.endsWith(ext)) {
            filename += ext;
        }
        filename = encodeUnicode(filename);
        link.setFinalFileName(filename);
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        doFree(link, false, 1, "free_directlink");
    }

    private void doFree(final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resumable, maxchunks);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setProperty(directlinkproperty, dllink);
        dl.startDownload();
    }

    private String checkDirectLink(final DownloadLink link, final String property) {
        String dllink = link.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                con = br2.openHeadConnection(dllink);
                if (!this.looksLikeDownloadableContent(con)) {
                    return null;
                } else {
                    return dllink;
                }
            } catch (final Exception e) {
                return null;
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return null;
    }

    public static String getDirectlinkFromJson(final Map<String, Object> entries) {
        String directlink = null;
        final String key = (String) JavaScriptEngineFactory.walkJson(entries, "file/key");
        if (key != null) {
            directlink = "http://img.hb.aicdn.com/" + key;
        }
        return directlink;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public String getDescription() {
        return "JDownloader's huaban.com plugin helps downloading pictures from huaban.com.";
    }

    public static final String  ENABLE_DESCRIPTION_IN_FILENAMES        = "ENABLE_DESCRIPTION_IN_FILENAMES";
    public static final boolean defaultENABLE_DESCRIPTION_IN_FILENAMES = false;

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), ENABLE_DESCRIPTION_IN_FILENAMES, JDL.L("plugins.hoster.HuabanCom.enableDescriptionInFilenames", "Add pind-escription to filenames?\r\nNOTE: If enabled, Filenames might get very long!")).setDefaultValue(defaultENABLE_DESCRIPTION_IN_FILENAMES));
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}