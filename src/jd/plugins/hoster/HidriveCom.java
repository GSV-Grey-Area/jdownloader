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

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.parser.UrlQuery;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "hidrive.com" }, urls = { "https?://(?:my\\.hidrive\\.com/lnk/|(?:www\\.)?hidrive\\.strato\\.com/wget)[A-Za-z0-9]+|https://my\\.hidrive\\.com/share/([^/]+)#file_id=(.+)" })
public class HidriveCom extends PluginForHost {
    public HidriveCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "https://hidrive.com/";
    }

    /* Connection stuff */
    private static final boolean FREE_RESUME                       = false;
    private static final int     FREE_MAXCHUNKS                    = 1;
    private static final int     FREE_MAXDOWNLOADS                 = 20;
    public static final String   PROPERTY_ACCESS_TOKEN             = "PROPERTY_ACCESS_TOKEN";
    public static final String   PROPERTY_ACCESS_TOKEN_VALID_UNTIL = "PROPERTY_ACCESS_TOKEN_VALID_UNTIL";
    public static final String   PROPERTY_DOWNLOAD_CODE            = "PROPERTY_DOWNLOAD_CODE";

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private static final String TYPE_SINGLE_FILE                   = "https?://(?:my\\.hidrive\\.com/lnk/|(?:www\\.)?hidrive\\.strato\\.com/wget)([A-Za-z0-9]+)";
    private static final String TYPE_SINGLE_FILE_AS_PART_OF_FOLDER = "https://my\\.hidrive\\.com/share/([^/]+)#file_id=(.+)";

    private String getFID(final DownloadLink link) {
        if (link.getPluginPatternMatcher() == null) {
            return null;
        } else if (link.getPluginPatternMatcher().matches(TYPE_SINGLE_FILE)) {
            return new Regex(link.getPluginPatternMatcher(), TYPE_SINGLE_FILE).getMatch(0);
        } else {
            /* TYPE_SINGLE_FILE_AS_PART_OF_FOLDER */
            return new Regex(link.getPluginPatternMatcher(), TYPE_SINGLE_FILE_AS_PART_OF_FOLDER).getMatch(0) + new Regex(link.getPluginPatternMatcher(), TYPE_SINGLE_FILE_AS_PART_OF_FOLDER).getMatch(1);
        }
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        return requestFileInformation(link, false);
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws IOException, PluginException {
        if (!link.isNameSet()) {
            /* Fallback */
            link.setName(this.getFID(link));
        }
        this.setBrowserExclusive();
        final Browser brAPI = prepBRAPI(br.cloneBrowser());
        if (link.getPluginPatternMatcher().matches(TYPE_SINGLE_FILE_AS_PART_OF_FOLDER)) {
            if (link.getLongProperty(PROPERTY_ACCESS_TOKEN_VALID_UNTIL, 0) <= System.currentTimeMillis()) {
                /* TODO: Maybe add refresh via crawler plugin */
                throw new PluginException(LinkStatus.ERROR_FATAL, "Access token expired");
            }
            URLConnectionAdapter con = null;
            try {
                con = br.openHeadConnection(generateDirectDownloadurl(link));
                if (this.looksLikeDownloadableContent(con)) {
                    if (con.getLongContentLength() > 0) {
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                    }
                    link.setFinalFileName(Plugin.getFileNameFromDispositionHeader(con));
                } else {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        } else {
            /* TYPE_SINGLE_FILE */
            final boolean useAPI = true;
            if (useAPI) {
                final UrlQuery query = new UrlQuery();
                query.add("id", this.getFID(link));
                String fields = "name,type,size,ttl,remaining,mime_type";
                if (isDownload) {
                    fields += ",download_code";
                }
                query.add("fields", Encoding.urlEncode(fields));
                String passCode = link.getDownloadPassword();
                if (link.isPasswordProtected() && passCode == null && isDownload) {
                    if (passCode == null) {
                        passCode = getUserInput("Password?", link);
                    }
                }
                if (passCode != null) {
                    query.add("password", Encoding.urlEncode(passCode));
                }
                brAPI.postPage("https://my.hidrive.com/api/sharelink/info", query);
                final Map<String, Object> entries = JSonStorage.restoreFromString(brAPI.toString(), TypeRef.HASHMAP);
                if (brAPI.getHttpConnection().getResponseCode() == 404 || brAPI.getHttpConnection().getResponseCode() == 410) {
                    /* {"msg":"Not Found: Invalid share id 'XXXXYYYY'","code":"404"} */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else if (brAPI.getHttpConnection().getResponseCode() == 401) {
                    /* Password protected file */
                    /* {"msg":"Unauthorized: Sharelink XXXXYYYY requires a password","code":"401"} */
                    link.setPasswordProtected(true);
                    /* Remove password if existant */
                    link.setDownloadPassword(null);
                    if (isDownload) {
                        throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
                    }
                } else {
                    link.setPasswordProtected(false);
                    link.setFinalFileName(URLEncode.decodeURIComponent(entries.get("name").toString()));
                    link.setVerifiedFileSize(((Number) entries.get("size")).longValue());
                    /* Required to be able to download the file (especially for password protected files). */
                    final String download_code = (String) entries.get("download_code");
                    if (!StringUtils.isEmpty(download_code)) {
                        link.setProperty(PROPERTY_DOWNLOAD_CODE, download_code);
                    }
                    if (passCode != null) {
                        link.setDownloadPassword(passCode);
                    }
                }
            } else {
                /* Old non-API handling */
                URLConnectionAdapter con = null;
                try {
                    con = br.openHeadConnection(generateDirectDownloadurl(link));
                    if (con.getResponseCode() == 401) {
                        /* Password protected file */
                        link.setPasswordProtected(true);
                        if (isDownload) {
                            /* TODO: Ask user for password */
                            throw new PluginException(LinkStatus.ERROR_FATAL, "Password protected items are not yet supported");
                        } else {
                            /* File is online but we cannot find any file info without knowing the password. */
                            return AvailableStatus.TRUE;
                        }
                    } else if (this.looksLikeDownloadableContent(con)) {
                        link.setPasswordProtected(false);
                        if (con.getLongContentLength() > 0) {
                            link.setVerifiedFileSize(con.getCompleteContentLength());
                        }
                        link.setFinalFileName(Plugin.getFileNameFromDispositionHeader(con));
                    } else {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                } finally {
                    try {
                        con.disconnect();
                    } catch (final Throwable e) {
                    }
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    public static Browser prepBRAPI(final Browser br) {
        br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        br.setAllowedResponseCodes(410);
        br.setFollowRedirects(true);
        return br;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        doFree(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private String generateDirectDownloadurl(final DownloadLink link) {
        if (link.getPluginPatternMatcher().matches(TYPE_SINGLE_FILE_AS_PART_OF_FOLDER)) {
            final String fileID = new Regex(link.getPluginPatternMatcher(), TYPE_SINGLE_FILE_AS_PART_OF_FOLDER).getMatch(1);
            return "https://my.hidrive.com/api/file?attachment=true&pid=" + Encoding.urlEncode(fileID) + "&access_token=" + link.getStringProperty(PROPERTY_ACCESS_TOKEN);
        } else if (link.hasProperty(PROPERTY_DOWNLOAD_CODE)) {
            /*
             * E.g. required for password protected files as user has to enter correct password in order to get this string in order to be
             * able to download.
             */
            return "https://my.hidrive.com/api/sharelink/download?id=" + this.getFID(link) + "&download_code=" + link.getStringProperty(PROPERTY_DOWNLOAD_CODE);
        } else {
            /* Alternative: https://my.hidrive.com/api/sharelink/download?id=<fid> */
            return "https://www.hidrive.strato.com/wget/" + this.getFID(link);
        }
    }

    private void doFree(final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        requestFileInformation(link, true);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, generateDirectDownloadurl(link), resumable, maxchunks);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 5 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 5 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl.startDownload();
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        return false;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}