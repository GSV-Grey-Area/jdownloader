//jDownloader - Downloadmanager
//Copyright (C) 2015  JD-Team support@jdownloader.org
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
package jd.plugins.decrypter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.requests.PostRequest;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.HTMLParser;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "fembed.com" }, urls = { "https?://(?:www\\.)?(av-th|javhd|javr|javrave|javnew)\\.(club|net|today)/[a-z0-9\\-_%]{5,}/" })
public class JavFembed extends PluginForDecrypt {
    public JavFembed(PluginWrapper wrapper) {
        super(wrapper);
    }

    private String title = null;

    @SuppressWarnings("deprecation")
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.setFollowRedirects(true);
        br.getPage(parameter);
        if (br.getHttpConnection() == null || br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("404 Not Found<|Page not found")) {
            decryptedLinks.add(createOfflinelink(parameter));
            return decryptedLinks;
        }
        /** 2021-02-17: Check for extra iframe containing fembed source URL javhd.today ticket 89512 */
        final String iframeRedirect = br.getRegex("<iframe id=\"main-player\" src=\"(https?://player\\.[^/]+/\\d+/?)\"").getMatch(0);
        if (iframeRedirect != null) {
            br.getPage(iframeRedirect);
        }
        /* 2021-02-17: javhd.today ticket 89512 */
        final String[] allExternalSources = br.getRegex("playEmbed\\('(https?://[^<>\"\\']+)'\\)").getColumn(0);
        if (allExternalSources.length > 0) {
            for (final String externalSource : allExternalSources) {
                decryptedLinks.add(this.createDownloadlink(externalSource));
            }
            return decryptedLinks;
        }
        title = Encoding.htmlDecode(br.getRegex("<title>(?:Watch Japanese Porn &ndash; )?(.*?)( \\| JAVNEW| &ndash; JavRave.club| - (JavSeen.Tv|1080HD|Jav Tsunami))?</title>").getMatch(0)).trim();
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(title);
        String fembed = br.getRegex("<iframe[^<>]*?src=\"([^<>]*?/v/.*?)\"").getMatch(0);
        if (fembed == null) {
            fembed = br.getRegex("allowfullscreen=[^<>]+?(http[^<>]+?)>").getMatch(0); // javr.club
        }
        if (fembed == null) {
            /* Fallback - crawl all URLs inside all iframes where they usually got their players. */
            final String[] iframes = br.getRegex("<iframe(.*?)</iframe>").getColumn(0);
            for (final String iframe : iframes) {
                final String[] urls = HTMLParser.getHttpLinks(iframe, br.getURL());
                for (final String url : urls) {
                    decryptedLinks.add(this.createDownloadlink(url));
                }
            }
            return decryptedLinks;
        }
        fembed = fembed.replace("\\", "");
        logger.info("Debug info: fembed: " + fembed);
        crawlFembedLink(decryptedLinks, fembed);
        fp.addLinks(decryptedLinks);
        return decryptedLinks;
    }

    private void crawlFembedLink(final ArrayList<DownloadLink> crawledLinks, final String fembed) throws Exception {
        // Copied from FEmbedDecrypter, thanks to Sebbu.
        String fembedHost = Browser.getHost(fembed);
        // logger.info("Debug info: fembedHost: " + fembedHost);
        if (fembedHost.contains("fembed")) {
            fembedHost = "www." + fembedHost;
        }
        String file_id = new Regex(fembed, "/(?:f|v|api/sources?)/([a-zA-Z0-9_-]+)").getMatch(0);
        final PostRequest postRequest = new PostRequest("https://" + fembedHost + "/api/source/" + file_id);
        final Map<String, Object> response = JSonStorage.restoreFromString(br.getPage(postRequest), TypeRef.HASHMAP);
        if (!Boolean.TRUE.equals(response.get("success"))) {
            final DownloadLink link = createDownloadlink(fembed.replaceAll("https?://", "decryptedforFEmbedHosterPlugin://"));
            link.setAvailable(false);
            crawledLinks.add(link);
            return;
        }
        final List<Map<String, Object>> videos;
        if (response.get("data") instanceof String) {
            videos = (List<Map<String, Object>>) JSonStorage.restoreFromString((String) response.get("data"), TypeRef.OBJECT);
        } else {
            videos = (List<Map<String, Object>>) response.get("data");
        }
        for (final Map<String, Object> video : videos) {
            final DownloadLink link = createDownloadlink(fembed.replaceAll("https?://", "decryptedforFEmbedHosterPlugin://"));
            final String label = (String) video.get("label");
            final String type = (String) video.get("type");
            link.setProperty("label", label);
            link.setProperty("fembedid", file_id);
            link.setProperty("fembedHost", fembedHost);
            if (!StringUtils.isEmpty(title)) {
                link.setFinalFileName(title + "-" + label + "." + type);
            } else {
                link.setName(file_id + "-" + label + "." + type);
            }
            link.setAvailable(true);
            crawledLinks.add(link);
        }
    }
}