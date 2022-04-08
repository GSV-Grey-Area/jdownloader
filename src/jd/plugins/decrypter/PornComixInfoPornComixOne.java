package jd.plugins.decrypter;

import java.util.ArrayList;

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "porncomixone.net" }, urls = { "https?://(?:www\\.)?porncomixone\\.net/comic/([a-z0-9\\-]+)" })
/** Formerly known as: porncomix.one */
public class PornComixInfoPornComixOne extends PluginForDecrypt {
    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        String addedurl = param.getCryptedUrl();
        br.getPage(addedurl);
        if (br.getHttpConnection().getResponseCode() == 404) {
            decryptedLinks.add(this.createOfflinelink(param.getCryptedUrl()));
            return decryptedLinks;
        }
        final String urltitle = new Regex(addedurl, this.getSupportedLinks()).getMatch(0);
        String postTitle = br.getRegex("(?i)<title>([^<>\"]+) \\&bull; Porn Comics One</title>").getMatch(0);
        if (StringUtils.isEmpty(postTitle)) {
            /* Fallback */
            postTitle = urltitle.replace("-", " ");
        } else {
            postTitle = Encoding.htmlDecode(postTitle);
        }
        String[] images = br.getRegex("(/gallery/[^<>\"\\']+)").getColumn(0);
        if (images != null) {
            for (String imageurl : images) {
                imageurl = br.getURL(imageurl).toString();
                final DownloadLink link = createDownloadlink(imageurl);
                link.setAvailable(true);
                link.setContainerUrl(addedurl);
                decryptedLinks.add(link);
            }
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(postTitle);
        fp.addLinks(decryptedLinks);
        return decryptedLinks;
    }
}
