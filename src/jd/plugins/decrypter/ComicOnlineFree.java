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
import java.util.Locale;

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "comiconlinefree.com" }, urls = { "https?://(www\\.)?(comiconlinefree\\.(?:com|net)|viewcomics\\.me)/(?:[^/]+/).+$" })
public class ComicOnlineFree extends antiDDoSForDecrypt {
    public ComicOnlineFree(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        String parameter = param.toString();
        br.setFollowRedirects(true);
        if (!new Regex(parameter, "/(comic/|issue-)").matches()) {
            getLogger().warning("Not a summary or issue page");
            return decryptedLinks;
        }
        if (new Regex(parameter, "/[^/]+/issue-[^/]+").matches() && !parameter.endsWith("/full")) {
            parameter = new Regex(parameter, "(.*/issue-[^/]+)").getMatch(0) + "/full";
        }
        getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        String fpName = br.getRegex("<title>\\s*([^<]+)Comic\\s*-\\s*Read\\s*[^<]+\\s+Online\\s+For\\s+Free").getMatch(0);
        if (StringUtils.isEmpty(fpName)) {
            fpName = br.getRegex("<title>\\s*([^>]+)\\s+-\\s+Read\\s+[^<]+\\s+Online\\s+").getMatch(0);
            if (StringUtils.isEmpty(fpName)) {
                getLogger().warning("Unable to determine comic/issue title, can't proceed without one.");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        final FilePackage fp = FilePackage.getInstance();
        if (StringUtils.isNotEmpty(fpName)) {
            fp.setName(Encoding.htmlDecode(fpName.trim()));
        }
        final String[] links = br.getRegex("<a[^>]+class\\s*=\\s*\"ch-name\"[^>]+href\\s*=\\s*\"([^\"]+)\"[^>]*>").getColumn(0);
        if (links != null && links.length > 0) {
            for (String link : links) {
                link = Encoding.htmlDecode(link);
                DownloadLink dl = createDownloadlink(link);
                fp.add(dl);
                distribute(dl);
            }
        }
        final String[] images = br.getRegex("<img[^>]+class\\s*=\\s*\"[^\"]+chapter_img\"[^>]+data-original\\s*=\\s*\"([^\"]+)\"[^>]*>").getColumn(0);
        if (images != null && images.length > 0) {
            final String chapter_name = Encoding.htmlDecode(fpName.trim());
            final int padlength = StringUtils.getPadLength(images.length);
            int page = 1;
            for (String image : images) {
                String page_formatted = String.format(Locale.US, "%0" + padlength + "d", page++);
                image = Encoding.htmlDecode(image);
                final DownloadLink dl = createDownloadlink("directhttp://" + image);
                String ext = getFileNameExtensionFromURL(image, ".jpg");
                dl.setFinalFileName(chapter_name + "_" + page_formatted + ext);
                fp.add(dl);
                distribute(dl);
            }
        }
        return decryptedLinks;
    }
}