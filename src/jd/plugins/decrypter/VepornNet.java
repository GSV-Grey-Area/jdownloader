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
import java.util.List;

import org.jdownloader.plugins.components.antiDDoSForDecrypt;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class VepornNet extends antiDDoSForDecrypt {
    public VepornNet(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "veporno.net", "veporn.net", "veporns.com" });
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/video/([A-Za-z0-9\\-_]+)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.setFollowRedirects(true);
        getPage(parameter);
        if (br.containsHTML(">Site is too crowded<")) {
            for (int i = 1; i <= 3; i++) {
                sleep(i * 3 * 1001l, param);
                getPage(parameter);
                if (!br.containsHTML(">Site is too crowded<")) {
                    break;
                }
            }
        }
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!this.canHandle(br.getURL())) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String fpName = br.getRegex("(rhgd)").getMatch(0);
        final String[] links = br.getRegex("comment\\((\\d+)\\)").getColumn(0);
        if (links.length > 0) {
            br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            int counter = 1;
            for (final String singleLink : links) {
                if (this.isAbort()) {
                    return ret;
                }
                final Browser br = this.br.cloneBrowser();
                getPage(br, "/ajax.php?page=video_play&thumb&theme=&video=&id=" + singleLink + "&server=" + counter);
                if (br.containsHTML(">Site is too crowded<")) {
                    for (int i = 1; i <= 3; i++) {
                        sleep(i * 3 * 1001l, param);
                        getPage(br, "/ajax.php?page=video_play&thumb&theme=&video=&id=" + singleLink + "&server=" + counter);
                        if (!br.containsHTML(">Site is too crowded<")) {
                            break;
                        }
                    }
                }
                String finallink = br.getRegex("iframe src='(https?[^<>']+)'").getMatch(0);
                if (finallink == null) {
                    finallink = br.getRegex("iframe src=\"(https?[^<>\"]+)\"").getMatch(0);
                    if (finallink == null) {
                        continue;
                    }
                }
                ret.add(createDownloadlink(finallink));
                counter++;
            }
        }
        /* 2022-06-21: Most likely videos embedded on streamtape.com */
        final String[] embedURLs = br.getRegex("<iframe src=\"(https?://[^\"]+)\"").getColumn(0);
        if (embedURLs.length > 0) {
            for (final String embedURL : embedURLs) {
                ret.add(this.createDownloadlink(embedURL));
            }
        }
        if (fpName != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            fp.addLinks(ret);
        }
        return ret;
    }
}
