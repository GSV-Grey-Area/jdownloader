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
import java.util.Set;

import org.appwork.utils.StringUtils;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.Request;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.Form.MethodType;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.PluginJSonUtils;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "bs.to" }, urls = { "https?://(?:www\\.)?(?:bs\\.to|burningseries\\.co)/(serie/.*|out/\\d+)" })
public class BsTo extends PluginForDecrypt {
    public BsTo(PluginWrapper wrapper) {
        super(wrapper);
        Browser.setRequestIntervalLimitGlobal("bs.to", 200);
    }

    private static final String TYPE_SINGLE = "https?://(www\\.)?(?:bs\\.to|burningseries\\.co)/serie/[^/]+/\\d+/[^/]+/[^/]+/[^/]+";

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        if (StringUtils.containsIgnoreCase(parameter, "bs.to/out") || StringUtils.containsIgnoreCase(parameter, "burningseries.co/out")) {
            br.setFollowRedirects(false);
            br.getPage(parameter);
            if (br.getRedirectLocation() == null || br.containsHTML("g-recaptcha")) {
                Form form = br.getFormbyProperty("id", "gateway");
                if (form == null) {
                    form = new Form();
                    form.setMethod(MethodType.GET);
                    form.setAction(br.getURL());
                }
                final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br).getToken();
                form.put("t", recaptchaV2Response);
                br.submitForm(form);
            }
            final String finallink = br.getRedirectLocation();
            decryptedLinks.add(createDownloadlink(finallink));
            return decryptedLinks;
        }
        br.setFollowRedirects(true);
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML(">Seite nicht gefunden<")) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        // final String urlpart = new Regex(parameter, "(serie/.+)").getMatch(0);
        if (parameter.matches(TYPE_SINGLE)) {
            String finallink = br.getRegex("\"(https?[^<>\"]*?)\" target=\"_blank\"><span class=\"icon link_go\"").getMatch(0);
            if (finallink == null) {
                finallink = br.getRegex("<iframe\\s+[^>]+src\\s*=\\s*(\"|'|)(.*?)\\1").getMatch(1);
                // hoster player
                if (finallink == null) {
                    finallink = br.getRegex("\"(https?[^<>\"]*?)\" target=\"_blank\" class=\"hoster-player\">").getMatch(0);
                    if (finallink == null) {
                        // final failover?
                        finallink = br.getRegex("https?://(\\w+\\.)?(?:bs\\.to|burningseries\\.co)/out/\\d+").getMatch(-1);
                    }
                }
            }
            if (finallink == null) {
                /* 2019-07-26: New */
                final String security_token = br.getRegex("<meta name=\"security_token\" content=\"([a-f0-9]+)\" />").getMatch(0);
                final String lid = br.getRegex("data\\-lid=\"(\\d+)\"").getMatch(0);
                if (security_token == null || lid == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                String rcKey = br.getRegex("<script>series\\.init\\s*\\(\\d+, \\d+, '([^<>\"\\']+)'\\);</script>").getMatch(0);
                if (rcKey == null) {
                    /* 2021-01-18: Hardcoded reCaptchaV2Key */
                    rcKey = "6LfG_SYaAAAAABmtgbmBRni8SvFepX0EEun1f5-5";
                }
                final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br, rcKey).getToken();
                br.postPage("/ajax/embed.php", "token=" + security_token + "&LID=" + lid + "&ticket=" + Encoding.urlEncode(recaptchaV2Response));
                finallink = PluginJSonUtils.getJson(br, "link");
                if (StringUtils.isEmpty(finallink) || !finallink.startsWith("http")) {
                    logger.warning("Failed to find finallink");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                /* 2019-07-26: Sadly we cannot re-use these tokens! */
                // this.getPluginConfig().setProperty("recaptchaV2Response", recaptchaV2Response);
            } else if (StringUtils.containsIgnoreCase(finallink, "bs.to/out") || StringUtils.containsIgnoreCase(finallink, "burningseries.co/out")) {
                br.setFollowRedirects(false);
                br.getPage(finallink);
                if (br.getRedirectLocation() == null || br.containsHTML("g-recaptcha")) {
                    final Form form = br.getForm(0);
                    if (form == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br).getToken();
                    form.put("token", Encoding.urlEncode(recaptchaV2Response));
                    br.submitForm(form);
                }
                finallink = br.getRedirectLocation();
            }
            decryptedLinks.add(createDownloadlink(finallink));
        } else {
            /* Crawl all mirrors of a single download */
            String mirrorlist = br.getRegex("<ul class=\"hoster-tabs top\">(.*?)<ul class=\"hoster-tabs bottom\">").getMatch(0);
            if (mirrorlist == null || mirrorlist.length() == 0) {
                /* Crawl all episodes of a series --> All mirrors in that */
                mirrorlist = br.getRegex("<table class=\"episodes\">.*?</table>").getMatch(-1);
            }
            final String[] mirrors = new Regex(mirrorlist, "<a[^>]*href=\"(/?[^\"]+)\"").getColumn(0);
            if (mirrors == null || mirrors.length == 0) {
                logger.warning("Decrypter broken for link: " + parameter);
                return null;
            }
            final Set<String> duplicate = new HashSet<String>();
            for (final String singleLink : mirrors) {
                logger.info("singleLink: " + singleLink);
                final String url = Request.getLocation("/" + singleLink, br.getRequest());
                if (duplicate.add(url)) {
                    decryptedLinks.add(createDownloadlink(url));
                }
            }
        }
        return decryptedLinks;
    }

    @Override
    public int getMaxConcurrentProcessingInstances() {
        return 5;
    }
}
