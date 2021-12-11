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

import org.appwork.utils.Hash;
import org.appwork.utils.Regex;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.controlling.linkcrawler.LinkCrawler;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.Plugin;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "ebay.com" }, urls = { "https?://(?:www\\.)?ebay[\\.\\w]+/itm/(\\d+).*" })
public class Ebay extends PluginForDecrypt {
    public Ebay(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl());
        final String fpName = br.getRegex("<title>([^<]+)\\s+eBay</title>").getMatch(0);
        final String itemID = new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0);
        String[] links = br.getRegex("\"maxImageUrl\":\"([^\"]+)\"").getColumn(0);
        for (String link : links) {
            final DownloadLink dl = createDownloadlink(Encoding.unicodeDecode(link));
            String filename = itemID + "_" + Hash.getMD5(link) + Plugin.getFileNameExtensionFromURL(link);
            dl.setFinalFileName(filename);
            decryptedLinks.add(dl);
        }
        if (fpName != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            fp.setProperty(LinkCrawler.PACKAGE_ALLOW_MERGE, true);
            fp.addLinks(decryptedLinks);
        }
        return decryptedLinks;
    }
}