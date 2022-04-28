//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import jd.PluginWrapper;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.utils.StringUtils;
import org.appwork.utils.parser.UrlQuery;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class ImageVenueCom extends PluginForHost {
    public ImageVenueCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "imagevenue.com" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            String regex = "https?://(?:www\\.)?img[0-9]+\\." + buildHostsPatternPart(domains) + "img\\.php\\?(loc=[^&]+\\&)?image=.{4,300}";
            regex += "|https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/view/o/\\?i=[^\\&]+\\&h=[^\\&]+";
            // galleries start with GA, images with ME?
            regex += "|https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?!GA)[A-Za-z0-9]+";
            regex += "|https?://cdn-images\\." + buildHostsPatternPart(domains) + "/[^/]+/[^/]+/[^/]+/[A-Za-z0-9]+[^/\\?]*(\\.png|\\.jpe?g)";
            regex += "|https?://cdno-data\\." + buildHostsPatternPart(domains) + "/html\\.[^/]+/upload\\d+/loc\\d+/[^/]+(\\.png|\\.jpe?g)";
            ret.add(regex);
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public void correctDownloadLink(DownloadLink link) throws Exception {
        final String url = link != null ? link.getPluginPatternMatcher() : null;
        final String cdnImageD = new Regex(url, "https?://cdn-images\\.[^/]*/[^/]+/[^/]+/[^/]+/([A-Za-z0-9]+)").getMatch(0);
        if (cdnImageD != null) {
            // rewrite cdn-images to normal urls
            link.setPluginPatternMatcher("https://www." + getHost() + "/" + cdnImageD);
        } else {
            final String cdnodata[] = new Regex(url, "https?://cdno-data\\.[^/]*/html\\.([^/]+)/upload\\d+/loc\\d+/([^/]+(?:\\.png|\\.jpe?g))").getRow(0);
            if (cdnodata != null) {
                // rewrite cdno-data to normal urls
                link.setPluginPatternMatcher("https://www." + getHost() + "/view/o/?i=" + cdnodata[1] + "&h=" + cdnodata[0]);
            }
        }
    }

    @Override
    public String getAGBLink() {
        return "http://imagevenue.com/tos.php";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    private String getFilenameURL(final DownloadLink link) throws MalformedURLException {
        final UrlQuery query = new UrlQuery().parse(link.getPluginPatternMatcher());
        String name = query.get("image");
        if (name == null) {
            name = query.get("i");
        }
        return name;
    }

    private String dllink = null;

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        /* Offline links should also have nice filenames */
        final String fallbackFilename = getFilenameURL(link);
        if (!link.isNameSet() && fallbackFilename != null) {
            link.setName(fallbackFilename);
        }
        this.br.setAllowedResponseCodes(500);
        br.setFollowRedirects(true);
        br.getPage(link.getPluginPatternMatcher());
        /* Error handling */
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getHttpConnection().getResponseCode() == 500) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("(?i)This image does not exist on this server|<title>404 Not Found</title>|>The requested URL /img\\.php was not found on this server\\.<")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        dllink = br.getRegex("id=(?:\"|\\')thepic(?:\"|\\')[^>]*?.*?SRC=(?:\"|\\')(.*?)(?:\"|\\')").getMatch(0);
        if (dllink == null && (br.containsHTML("Continue to your image") || br.containsHTML("Continue to ImageVenue"))) {
            br.getPage(link.getDownloadURL());
            dllink = br.getRegex("id=(?:\"|\\')thepic(?:\"|\\')[^>]*?.*?SRC=(?:\"|\\')(.*?)(?:\"|\\')").getMatch(0);
        }
        if (dllink == null) {
            /* 2020-06-22 */
            dllink = br.getRegex("data-toggle=\"full\">\\s*<img src=\"(https?://[^<>\"]+)\"").getMatch(0);
        } else {
            if (dllink == null) {
                if (br.containsHTML("tempval\\.focus\\(\\)")) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else {
                    logger.warning("Could not find finallink reference");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            final String server = new Regex(link.getDownloadURL(), "(img[0-9]+\\.imagevenue\\.com/)").getMatch(0);
            dllink = "https://" + server + dllink;
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        String filename = br.getRegex("<title>\\s*(?:ImageVenue.com\\s*-)?\\s*(.*?)\\s*</title>").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("src=\"https?://[^\"]+\"[^>]*alt=\"([^<>\"]+)\"").getMatch(0);
        }
        if (!StringUtils.isEmpty(filename)) {
            link.setFinalFileName(Encoding.htmlDecode(filename).trim());
        }
        URLConnectionAdapter con = null;
        try {
            con = br.openHeadConnection(dllink);
            if (!this.looksLikeDownloadableContent(con)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (con.getCompleteContentLength() == 14396) {
                /* 2021-08-27: Special "404 not image unavailable" dummy picture. */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                final long size = con.getCompleteContentLength();
                if (size > 0) {
                    link.setVerifiedFileSize(size);
                }
            }
        } finally {
            try {
                if (con != null) {
                    con.disconnect();
                }
            } catch (final Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        if (StringUtils.isEmpty(dllink)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 1);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}