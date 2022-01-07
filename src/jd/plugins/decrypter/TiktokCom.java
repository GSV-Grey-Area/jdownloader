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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.appwork.utils.StringUtils;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "tiktok.com" }, urls = { "https?://[A-Za-z0-9]+\\.tiktok\\.com/.+" })
public class TiktokCom extends PluginForDecrypt {
    public TiktokCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String TYPE_USER = ".+tiktok\\.com/(?:@|share/user/\\d+)(.+)";

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final PluginForHost plg = JDUtilities.getPluginForHost(this.getHost());
        if (param.getCryptedUrl().matches("https?://vm\\..+")) {
            /* Single redirect URLs */
            br.setFollowRedirects(false);
            br.getPage(param.getCryptedUrl().replaceFirst("http://", "https://"));
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String finallink = br.getRedirectLocation();
            if (finallink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            decryptedLinks.add(createDownloadlink(finallink));
        } else if (plg.canHandle(param.getCryptedUrl())) {
            /* Single URL for host plugin */
            decryptedLinks.add(this.createDownloadlink(param.getCryptedUrl()));
            return decryptedLinks;
        } else if (param.getCryptedUrl().matches(TYPE_USER)) {
            crawlProfile(param, decryptedLinks);
        } else {
            logger.info("Unsupported URL: " + param.getCryptedUrl());
        }
        return decryptedLinks;
    }

    public ArrayList<DownloadLink> crawlProfile(final CryptedLink param, final ArrayList<DownloadLink> decryptedLinks) throws Exception {
        br.setFollowRedirects(true);
        final String usernameSlug = new Regex(param.getCryptedUrl(), TYPE_USER).getMatch(0);
        if (usernameSlug == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (jd.plugins.hoster.TiktokCom.isBotProtectionActive(this.br)) {
            throw new DecrypterRetryException(RetryReason.CAPTCHA, "Bot protection active, cannot crawl any items of user " + usernameSlug, null, null);
        }
        String websiteJson = br.getRegex("window\\.__INIT_PROPS__ = (\\{.*?\\})</script>").getMatch(0);
        if (websiteJson == null) {
            websiteJson = br.getRegex("<script\\s*id\\s*=\\s*\"__NEXT_DATA__\"[^>]*>\\s*(\\{.*?\\})\\s*</script>").getMatch(0);
        }
        if (websiteJson != null) {
            /* Old handling with broken pagination (API sign handling missing), see: https://svn.jdownloader.org/issues/86758 */
            Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(websiteJson);
            final Map<String, Object> user_data = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "props/pageProps/userInfo/user");
            final String secUid = (String) user_data.get("secUid");
            final String userId = (String) user_data.get("id");
            final String username = (String) user_data.get("uniqueId");
            // final String username = new Regex(parameter, "/@([^/\\?\\&]+)").getMatch(0);
            if (StringUtils.isEmpty(secUid) || StringUtils.isEmpty(userId) || StringUtils.isEmpty(username)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(username);
            List<Object> ressourcelist;
            final boolean paginationBroken = true;
            if (paginationBroken) {
                logger.warning("Plugin not yet finished, API signing is missing");
                /* We can only return the elements we find on their website when we cannot use their API! */
                ressourcelist = (ArrayList<Object>) JavaScriptEngineFactory.walkJson(entries, "props/pageProps/items");
                for (final Object videoO : ressourcelist) {
                    entries = (Map<String, Object>) videoO;
                    final String videoID = (String) entries.get("id");
                    if (StringUtils.isEmpty(videoID)) {
                        /* Skip invalid items */
                        continue;
                    }
                    /* Mimic filenames which host plugin would set */
                    final String description = (String) entries.get("desc");
                    final long createTime = ((Number) entries.get("createTime")).longValue();
                    SimpleDateFormat target_format = new SimpleDateFormat("yyyy-MM-dd");
                    /* Timestamp */
                    final Date theDate = new Date(createTime * 1000);
                    final String dateFormatted = target_format.format(theDate);
                    final String videoURL = "https://www.tiktok.com/@" + usernameSlug + "/video/" + videoID;
                    final DownloadLink dl = this.createDownloadlink(videoURL);
                    dl.setName(dateFormatted + "_@" + usernameSlug + "_" + videoID + ".mp4");
                    dl.setComment(description);
                    dl.setAvailable(true);
                    dl._setFilePackage(fp);
                    decryptedLinks.add(dl);
                }
                return decryptedLinks;
            }
            boolean hasMore = true;
            int index = 0;
            int page = 1;
            // final int maxItemsPerPage = 48;
            String maxCursor = "0";
            do {
                logger.info("Current page: " + page);
                logger.info("Current index: " + index);
                br.getPage("https://m." + this.getHost() + "/share/item/list?secUid=" + secUid + "&id=" + userId + "&type=1&count=48&minCursor=0&maxCursor=" + maxCursor + "&_signature=TODO_FIXME");
                if (jd.plugins.hoster.TiktokCom.isBotProtectionActive(this.br)) {
                    throw new DecrypterRetryException(RetryReason.CAPTCHA, "Bot protection active, cannot crawl more items of user " + usernameSlug, null, null);
                }
                entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
                entries = (Map<String, Object>) entries.get("body");
                hasMore = ((Boolean) entries.get("hasMore")).booleanValue();
                maxCursor = (String) entries.get("maxCursor");
                ressourcelist = (List<Object>) entries.get("itemListData");
                for (final Object videoO : ressourcelist) {
                    entries = (Map<String, Object>) videoO;
                    entries = (Map<String, Object>) entries.get("itemInfos");
                    final String videoID = (String) entries.get("id");
                    final long createTimestamp = JavaScriptEngineFactory.toLong(entries.get("createTime"), 0);
                    if (StringUtils.isEmpty(videoID) || createTimestamp == 0) {
                        /* This should never happen */
                        return null;
                    }
                    final DownloadLink dl = this.createDownloadlink("https://www.tiktok.com/@" + username + "/video/" + videoID);
                    final String date_formatted = formatDate(createTimestamp);
                    dl.setFinalFileName(date_formatted + "_@" + username + "_" + videoID + ".mp4");
                    dl.setAvailable(true);
                    dl._setFilePackage(fp);
                    decryptedLinks.add(dl);
                    distribute(dl);
                    index++;
                }
                page++;
            } while (!this.isAbort() && hasMore && !StringUtils.isEmpty(maxCursor));
        } else {
            /* 2022-01-07: New simple handling */
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(usernameSlug);
            final String[] videoIDs = br.getRegex(usernameSlug + "/video/(\\d+)\"").getColumn(0);
            for (final String videoID : videoIDs) {
                final DownloadLink dl = this.createDownloadlink("https://www.tiktok.com/@" + usernameSlug + "/video/" + videoID);
                dl.setName("@" + usernameSlug + "_" + videoID + ".mp4");
                dl.setAvailable(true);
                dl._setFilePackage(fp);
                decryptedLinks.add(dl);
            }
        }
        return decryptedLinks;
    }

    public static String formatDate(final long date) {
        if (date <= 0) {
            return null;
        }
        String formattedDate = null;
        final String targetFormat = "yyyy-MM-dd";
        Date theDate = new Date(date * 1000);
        try {
            final SimpleDateFormat formatter = new SimpleDateFormat(targetFormat);
            formattedDate = formatter.format(theDate);
        } catch (Exception e) {
            /* prevent input error killing plugin */
            formattedDate = Long.toString(date);
        }
        return formattedDate;
    }
}
