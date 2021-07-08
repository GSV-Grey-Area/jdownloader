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

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Request;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.components.PluginJSonUtils;

/**
 *
 * @author raztoki
 *
 */
@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "nhentai.net" }, urls = { "https?://(?:www\\.)?nhentai\\.(?:net|to)/g/(\\d+)/" })
public class NhentaiNet extends antiDDoSForDecrypt {
    public NhentaiNet(PluginWrapper wrapper) {
        super(wrapper);
    }

    public int getMaxConcurrentProcessingInstances() {
        /* 2020-06-25: Too many requests can lead to failures */
        return 1;
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        final String galleryID = new Regex(parameter, this.getSupportedLinks()).getMatch(0);
        br.setFollowRedirects(true);
        getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        String title = null;
        try {
            String json = br.getRegex("JSON\\.parse\\(\"(\\{.*?)\"\\);").getMatch(0);
            json = PluginJSonUtils.unescape(json);
            Map<String, Object> entries = JSonStorage.restoreFromString(json, TypeRef.HASHMAP);
            Map<String, Object> titles = (Map<String, Object>) entries.get("title");
            title = br.getRegex("id\\s*=\\s*\"info\"\\s*>\\s*<h1>\\s*(.*?)\\s*</h1>").getMatch(0);
            title = (String) titles.get("english");
            if (StringUtils.isEmpty(title)) {
                title = (String) titles.get("english");
            }
        } catch (final Throwable e) {
            logger.log(e);
        }
        if (StringUtils.isEmpty(title)) {
            /* Fallback */
            title = galleryID + " - nhentai gallery";
        } else {
            /**
             * 2021-02-08: Avoid merging of packages with the same name but different contents: Galleries can have the exact name but
             * different content!
             */
            title = galleryID + "_" + title;
        }
        title = Encoding.htmlDecode(title);
        // images
        final String[] imgs = br.getRegex("class\\s*=\\s*\"gallerythumb\"\\s*href\\s*=\\s*\"/g/\\d+/\\d+/?\"[^<]*?<img\\s*(?:is=\"[^\"]*lazyload-image[^\"]*\")?\\s*class\\s*=\\s*\"[^\"]*lazyload[^\"]*\"[^>]+data-src\\s*=\\s*\"(.*?)\"").getColumn(0);
        if (imgs == null || imgs.length == 0) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final int numberOfPages = imgs.length;
        final DecimalFormat df = numberOfPages > 999 ? new DecimalFormat("0000") : numberOfPages > 99 ? new DecimalFormat("000") : new DecimalFormat("00");
        int i = 0;
        for (final String img : imgs) {
            final String link = Request.getLocation(img.replace("//t.", "//i.").replaceFirst("/(\\d+)t(\\.[a-z0-9]+)$", "/$1$2"), br.getRequest());
            final DownloadLink dl = createDownloadlink("directhttp://" + link);
            dl.setFinalFileName(df.format(++i) + getFileNameExtensionFromString(img, ".jpg"));
            dl.setAvailable(true);
            decryptedLinks.add(dl);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(title);
        fp.addLinks(decryptedLinks);
        // fp.setProperty(LinkCrawler.PACKAGE_ALLOW_MERGE, false);
        return decryptedLinks;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}