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
import java.util.LinkedHashMap;

import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "newgrounds.com" }, urls = { "https?://(?:\\w+\\.)?newgrounds\\.com/(?:art|audio|movies|games)(/view/[A-Za-z0-9\\-_]+/[A-Za-z0-9\\-_]+)?/?$" })
public class NewgroundsComDecrypter extends PluginForDecrypt {
    public NewgroundsComDecrypter(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String TYPE_ART   = ".+/art/?$";
    private static final String TYPE_AUDIO = ".+/audio/?$";

    @Override
    public int getMaxConcurrentProcessingInstances() {
        return 1;
    }

    @Override
    public void init() {
        try {
            /* 2020-10-26: They have quite strict rate-limits ... */
            Browser.setRequestIntervalLimitGlobal(getHost(), 1000);
        } catch (final Throwable e) {
        }
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final String parameter = param.toString();
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        String fpName = null;
        if (parameter.contains("/view/")) {
            /*
             * 2020-02-03: New: Such URLs may contain multiple URLs --> Crawl all of them. This linktype was initially handled in the
             * hosterplugin.
             */
            fpName = new Regex(parameter, "/view/(.+)").getMatch(0);
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(fpName);
            final String[] pics = br.getRegex("\"(https?://art\\.ngfiles\\.com/(?:comments|images)/[^<>\"]+)\"").getColumn(0);
            if (pics == null || pics.length == 0) {
                return null;
            }
            for (final String pic : pics) {
                final DownloadLink dl = this.createDownloadlink("directhttp://" + pic);
                dl.setAvailable(true);
                decryptedLinks.add(dl);
                dl._setFilePackage(fp);
            }
        } else {
            /* All items of a user */
            fpName = new Regex(parameter, "https?://([^/]+)\\.newgrounds\\.com/").getMatch(0);
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(fpName);
            int page = 0;
            String nextPage = null;
            do {
                page++;
                final String json;
                if (page == 1) {
                    json = br.getRegex("var years = (\\{.*?)render\\(years\\);").getMatch(0);
                } else {
                    br.getHeaders().put("accept", "application/json, text/javascript, */*; q=0.01");
                    br.getHeaders().put("x-requested-with", "XMLHttpRequest");
                    // br.getHeaders().put("", "");
                    br.getPage(nextPage);
                    json = br.toString();
                }
                LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaMap(json);
                nextPage = (String) entries.get("more");
                final LinkedHashMap<String, Object> years = (LinkedHashMap<String, Object>) entries.get("years");
                final ArrayList<Object> sequence = (ArrayList<Object>) entries.get("sequence");
                for (final Object sequenceO : sequence) {
                    final String year = Long.toString(JavaScriptEngineFactory.toLong(sequenceO, 0));
                    entries = (LinkedHashMap<String, Object>) years.get(year);
                    final ArrayList<Object> items = (ArrayList<Object>) entries.get("items");
                    for (final Object itemO : items) {
                        final String html = (String) itemO;
                        String title = new Regex(html, "title=\"([^<>\"]+)\"").getMatch(0);
                        if (title == null) {
                            title = new Regex(html, "alt=\"([^<>\"]+)\"").getMatch(0);
                        }
                        String url = new Regex(html, "((?:https?:)?//(?:\\w+\\.)?newgrounds\\.com/(?:(?:art|portal)/view|audio/listen)/[^<>\"\\']+)").getMatch(0);
                        if (StringUtils.isEmpty(url)) {
                            continue;
                        }
                        if (url.startsWith("//")) {
                            url = "https:" + url;
                        }
                        final DownloadLink dl = createDownloadlink(url);
                        if (title != null) {
                            title = Encoding.htmlDecode(title);
                            dl.setName(title);
                        }
                        // dl.setAvailable(true);
                        dl._setFilePackage(fp);
                        if (url.matches(TYPE_AUDIO)) {
                            dl.setMimeHint(CompiledFiletypeFilter.AudioExtensions.MP3);
                        } else if (url.matches(TYPE_ART)) {
                            dl.setMimeHint(CompiledFiletypeFilter.ImageExtensions.JPG);
                        } else {
                            /* movies & games */
                            dl.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
                        }
                        decryptedLinks.add(dl);
                        distribute(dl);
                    }
                }
            } while (!StringUtils.isEmpty(nextPage) && !nextPage.equals("null") && !this.isAbort());
        }
        return decryptedLinks;
    }
}
