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
package jd.plugins.decrypter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class CyberdropMeAlbum extends PluginForDecrypt {
    public CyberdropMeAlbum(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "cyberdrop.me" });
        ret.add(new String[] { "bunkr.is" });// same template/system?
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
            String regex = "https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/a/[A-Za-z0-9]+";
            regex += "|https?://stream\\." + buildHostsPatternPart(domains) + "/v/[^/]+\\.mp4";
            regex += "|https?://cdn\\." + buildHostsPatternPart(domains) + "/[^/]+\\.mp4";
            ret.add(regex);
        }
        return ret.toArray(new String[0]);
    }

    private static final String TYPE_ALBUM   = "https?://[^/]+/a/([A-Za-z0-9]+)";
    private static final String TYPE_VIDEO   = "https?://stream\\.[^/]+/v/(.+\\.mp4)";
    private static final String TYPE_VIDEO_2 = "https?://cdn\\.[^/]+/(.+\\.mp4)";

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        if (param.getCryptedUrl().matches(TYPE_VIDEO) || param.getCryptedUrl().matches(TYPE_VIDEO_2)) {
            final String directurl = correctDirecturl(param.getCryptedUrl());
            final DownloadLink dl = this.createDownloadlink(directurl);
            decryptedLinks.add(dl);
        } else {
            /* TYPE_ALBUM */
            br.getPage(param.getCryptedUrl());
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String albumjs = br.getRegex("const albumData\\s*=\\s*(\\{.*?\\})").getMatch(0);
            String fpName = new Regex(albumjs, "name\\s*:\\s*'([^\\']+)'").getMatch(0);
            if (fpName == null) {
                fpName = br.getRegex("<h1 id=\"title\"[^>]*title=\"([^\"]+)\"[^>]*>").getMatch(0);
                if (fpName == null) {
                    // bunkr.is
                    fpName = br.getRegex("<h1 id=\"title\"[^>]*>\\s*(.*?)\\s*<").getMatch(0);
                }
            }
            final HashSet<String> dups = new HashSet<String>();
            final String albumDescription = br.getRegex("<span id=\"description-box\"[^>]*>([^<>\"]+)</span>").getMatch(0);
            /* 2022-01-04: New, for albums with mixed content (e.g. NOT only photos). */
            final String[] htmls = br.getRegex("<div class=\"image-container column\"[^>]*>(.*?)/p>\\s*</div>").getColumn(0);
            for (final String html : htmls) {
                String directurl = new Regex(html, "href=\"(https?://[^\"]+)\"").getMatch(0);
                directurl = correctDirecturl(directurl);
                String filename = new Regex(html, "target=\"_blank\" title=\"([^<>\"]+)\"").getMatch(0);
                if (filename == null) {
                    // bunkr.is
                    filename = new Regex(html, "<p\\s*class\\s*=\\s*\"name\"\\s*>\\s*(.*?)\\s*<").getMatch(0);
                }
                final String filesizeBytes = new Regex(html, "class=\"(?:is-hidden)?\\s*file-size\"[^>]*>(\\d+) B").getMatch(0);
                if (dups.add(directurl)) {
                    final DownloadLink dl = this.createDownloadlink(directurl);
                    dl.setAvailable(true);
                    if (filename != null) {
                        dl.setFinalFileName(filename);
                    }
                    if (filesizeBytes != null) {
                        dl.setVerifiedFileSize(Long.parseLong(filesizeBytes));
                    }
                    decryptedLinks.add(dl);
                }
            }
            final String json = br.getRegex("dynamicEl\\s*:\\s*(\\[\\s*\\{.*?\\])").getMatch(0);
            if (json != null) {
                /* gallery mode only works for images */
                final List<Map<String, Object>> ressourcelist = (List<Map<String, Object>>) JavaScriptEngineFactory.jsonToJavaObject(json);
                for (final Map<String, Object> photo : ressourcelist) {
                    final String downloadUrl = (String) photo.get("downloadUrl");
                    if (dups.add(downloadUrl)) {
                        final String subHtml = (String) photo.get("subHtml");
                        final String filesizeStr = new Regex(subHtml, "(\\d+(\\.\\d+)? [A-Za-z]{2,5})$").getMatch(0);
                        final DownloadLink dl = this.createDownloadlink(downloadUrl);
                        dl.setAvailable(true);
                        if (filesizeStr != null) {
                            dl.setDownloadSize(SizeFormatter.getSize(filesizeStr));
                        }
                        decryptedLinks.add(dl);
                    }
                }
            }
            if (decryptedLinks.size() == 0) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            if (fpName != null) {
                final FilePackage fp = FilePackage.getInstance();
                fp.setName(Encoding.htmlDecode(fpName.trim()));
                if (!StringUtils.isEmpty(albumDescription)) {
                    fp.setComment(albumDescription);
                }
                fp.addLinks(decryptedLinks);
            }
        }
        return decryptedLinks;
    }

    /** 2022-03-14: Especially required for bunkr.is video-URLs. */
    private String correctDirecturl(final String url) {
        if (url.matches(TYPE_VIDEO)) {
            return "https://media-files." + this.getHost() + "/" + new Regex(url, TYPE_VIDEO).getMatch(0);
        } else if (url.matches(TYPE_VIDEO_2)) {
            return "https://media-files." + this.getHost() + "/" + new Regex(url, TYPE_VIDEO_2).getMatch(0);
        } else {
            return url;
        }
    }
}
