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
import java.util.Random;

import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.components.UserAgents;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "urlgalleries.net" }, urls = { "https?://(?:[a-z0-9_\\-]+\\.)?urlgalleries\\.net/(porn-gallery-\\d+/.*|blog_gallery\\.php\\?id=\\d+.*|porn-picture-.+)|https?://go\\.urlgalleries\\.net/[a-z0-9]+" })
public class RlGalleriesNt extends PluginForDecrypt {
    private static String agent = null;

    public RlGalleriesNt(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    private static final String TYPE_SINGLE_PICTURE = "https?://[^/]+/porn-picture-.+";

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString().replace("http://", "https://");
        br.setReadTimeout(3 * 60 * 1000);
        // br.setCookie(".urlgalleries.net", "popundr", "1");
        if (agent == null) {
            agent = UserAgents.stringUserAgent();
        }
        br.getHeaders().put("User-Agent", agent);
        br.getHeaders().put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        br.getHeaders().put("Accept-Language", "en-gb, en;q=0.9");
        br.setFollowRedirects(true);
        final String galleryID = new Regex(parameter, "(?:porn-gallery-|blog_gallery\\.php\\?id=)(\\d+)").getMatch(0);
        if (galleryID == null || parameter.matches(TYPE_SINGLE_PICTURE)) {
            /* 2020-03-19: Single link */
            br.setFollowRedirects(false);
            br.getPage(parameter);
            int counter = 0;
            String redirect = null;
            do {
                counter++;
                redirect = br.getRedirectLocation();
                if (redirect == null || !redirect.contains(this.getHost())) {
                    break;
                }
                br.getPage(redirect);
            } while (counter <= 5);
            if (isOffline() || br.containsHTML("/not_found_adult\\.php") || (redirect != null && redirect.contains(this.getHost()))) {
                decryptedLinks.add(this.createOfflinelink(parameter));
                return decryptedLinks;
            }
            final String finallink;
            if (redirect != null) {
                finallink = redirect;
            } else {
                finallink = br.getRegex("linkDestUrl\\s*=\\s*\\'(http[^<>\"\\']+)\\'").getMatch(0);
            }
            if (finallink == null) {
                return null;
            }
            decryptedLinks.add(this.createDownloadlink(finallink));
            return decryptedLinks;
        }
        final String domain = Browser.getHost(parameter, true);
        /* Display as many items as possible to avoid having to deal with pagination. */
        // br.getPage(parameter);
        br.getPage(String.format("https://%s/porn-gallery-%s//Lycia&a=10000", domain, galleryID));
        if (isOffline()) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        String fpName = br.getRegex("border='0' /></a></div>(?:\\s*<h\\d+[^>]*>\\s*)?(.*?)(?:\\s*</h\\d+>\\s*)?</td></tr><tr>").getMatch(0);
        if (fpName == null) {
            fpName = br.getRegex("<title>([^<]*?)</title>").getMatch(0);
        }
        FilePackage fp = FilePackage.getInstance();
        if (fpName != null) {
            fp.setName(fpName.trim());
        }
        int page = 0;
        String nextpage = null;
        do {
            page++;
            logger.info("Crawling page " + page + " of ??");
            if (nextpage != null) {
                br.getPage(nextpage);
            }
            nextpage = br.getRegex("(/blog_gallery\\.php[^<>\"\\']+\\&p=" + (page + 1) + "[^<>\"\\']*)").getMatch(0);
            String[][] items = br.getRegex("href='(/porn-picture[^']+)'[^<>]+><[^<>]+title=\"([^\"]+)\"").getMatches();
            if (items == null || items.length == 0) {
                logger.warning("Decrypter broken for link: " + parameter);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            int counter = 1;
            /*
             * 2019-05-15: Via browser it sometimes worked when using a private tab but so far I havent found a way to avoid their captcha.
             */
            final boolean tryToAvoidCaptcha = false;
            // TODO
            Browser brc;
            if (tryToAvoidCaptcha) {
                brc = new Browser();
            } else {
                brc = br.cloneBrowser();
            }
            brc.setFollowRedirects(false);
            final HashSet<String> dups = new HashSet<String>();
            boolean noNewItems = true;
            for (final String item[] : items) {
                String aLink = item[0];
                if (!dups.add(aLink)) {
                    continue;
                }
                noNewItems = false;
                logger.info("Decrypting link " + counter + " of " + items.length);
                sleep(new Random().nextInt(3) + 1000, param);
                try {
                    if (tryToAvoidCaptcha) {
                        brc = new Browser();
                        brc.getHeaders().put("User-Agent", UserAgents.stringUserAgent());
                        brc.getPage(parameter);
                    }
                    brc.getPage("https://" + this.getHost() + aLink);
                } catch (final Exception e) {
                    logger.log(e);
                    logger.info("Link timed out: " + aLink);
                    counter++;
                    continue;
                }
                String finallink = brc.getRedirectLocation();
                if (brc.containsHTML("Suspicious activity detected, please confirm")) {
                    final String rcKey = brc.getRegex("data\\-sitekey=\"([^<>\"]+)\"").getMatch(0);
                    if (rcKey == null) {
                        logger.warning("Captcha handling failed");
                        break;
                    }
                    final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br, rcKey).getToken();
                    // {\"data\":{\"response\":\"\"}}
                    brc.postPageRaw("/unban.php", "{\"response\":\"" + recaptchaV2Response + "\"}");
                    brc.getPage(aLink);
                    final String error = PluginJSonUtils.getJson(brc, "error");
                    if (error != null) {
                        /* 2019-05-15: E.g. "{"error":"Captcha not ok"}" */
                        logger.info("Captcha failure");
                        throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                    }
                    finallink = brc.getRedirectLocation();
                }
                if (finallink == null) {
                    logger.warning("Decrypter broken for link: " + parameter);
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final DownloadLink dl = createDownloadlink(finallink);
                // Give temp name so we have no same filenames
                dl.setName(Integer.toString(new Random().nextInt(1000000000)));
                if (fp.getName() != null) {
                    fp.add(dl);
                }
                decryptedLinks.add(dl);
                dl.setFinalFileName(item[1]);
                /* 2020-03-20: Set all to available for faster crawling */
                dl.setAvailable(true);
                distribute(dl);
                logger.info(finallink);
                counter++;
                if (isAbort()) {
                    logger.info("Decryption process aborted by user, stopping...");
                    break;
                }
            }
            if (noNewItems) {
                /* Fail-safe */
                logger.info("Stopping because: Failed to find any new item on this page");
                break;
            }
        } while (nextpage != null);
        return decryptedLinks;
    }

    private boolean isOffline() {
        return br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("<title> - urlgalleries\\.net</title>|>ERROR - NO IMAGES AVAILABLE") || br.getURL().contains("/not_found_adult.php");
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}