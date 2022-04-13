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

import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "pornsteep.com", "frigtube.com", "vidxporn.com", "porndull.com" }, urls = { "https?://(?:www\\.)?pornsteep\\.com/video/([a-z0-9\\-]+)\\-(\\d+)\\.html", "https?://(?:www\\.)?frigtube\\.com/video/([a-z0-9\\-]+)\\-(\\d+)\\.html", "https?://(?:www\\.)?vidxporn\\.com/video/([a-z0-9\\-]+)\\-(\\d+)\\.html", "https?://(?:www\\.)?porndull\\.com/video/([a-z0-9\\-]+)\\-(\\d+)\\.html" })
public class UnknownPornScript1Crawler extends PornEmbedParser {
    public UnknownPornScript1Crawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "pornsteep.com" });
        ret.add(new String[] { "frigtube.com" });
        ret.add(new String[] { "vidxporn.com" });
        ret.add(new String[] { "porndull.com" });
        ret.add(new String[] { "dansmovies.com" });
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/video/([a-z0-9\\-]+)\\-(\\d+)\\.html");
        }
        return ret.toArray(new String[0]);
    }
    // public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
    // final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
    // final String parameter = param.toString();
    // br.setFollowRedirects(true);
    // br.getPage(parameter);
    // if (jd.plugins.hoster.UnknownPornScript1.isOffline(this.br)) {
    // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
    // }
    // final String title = new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0).replace("-", " ");
    // decryptedLinks.addAll(findEmbedUrls(title));
    // if (decryptedLinks.size() == 0) {
    // /* Probably selfhosted content --> Pass to hostplugin */
    // final DownloadLink selfhosted = this.createDownloadlink(param.getCryptedUrl());
    // selfhosted.setAvailable(true);
    // selfhosted.setName(title + ".mp4");
    // decryptedLinks.add(selfhosted);
    // }
    // return decryptedLinks;
    // }

    @Override
    protected boolean isOffline(final Browser br) {
        return jd.plugins.hoster.UnknownPornScript1.isOffline(this.br);
    }

    @Override
    protected String getFileTitle(final CryptedLink param, final Browser br) {
        return new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0).replace("-", " ").trim();
    }

    @Override
    protected boolean assumeSelfhostedContentOnNoResults() {
        return true;
    }
}