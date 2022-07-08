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

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.components.archiveorg.ArchiveOrgConfig;
import org.jdownloader.plugins.components.archiveorg.ArchiveOrgConfig.BookCrawlMode;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.AccountRequiredException;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.ArchiveOrg;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "archive.org", "subdomain.archive.org" }, urls = { "https?://(?:www\\.)?archive\\.org/(?:details|download|stream|embed)/(?!copyrightrecords)@?.+", "https?://[^/]+\\.archive\\.org/view_archive\\.php\\?archive=[^\\&]+(?:\\&file=[^\\&]+)?" })
public class ArchiveOrgCrawler extends PluginForDecrypt {
    public ArchiveOrgCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    private boolean isArchiveURL(final String url) throws MalformedURLException {
        if (url == null) {
            return false;
        } else {
            final UrlQuery query = UrlQuery.parse(url);
            return url.contains("view_archive.php") && query.get("file") == null;
        }
    }

    final Set<String>  dups       = new HashSet<String>();
    private ArchiveOrg hostPlugin = null;

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        br.setFollowRedirects(true);
        hostPlugin = (ArchiveOrg) getNewPluginForHostInstance("archive.org");
        param.setCryptedUrl(param.getCryptedUrl().replace("://www.", "://").replaceFirst("/(stream|embed)/", "/download/"));
        /*
         * 2020-08-26: Login might sometimes be required for book downloads.
         */
        final Account account = AccountController.getInstance().getValidAccount("archive.org");
        if (account != null) {
            hostPlugin.login(account, false);
        }
        URLConnectionAdapter con = null;
        boolean isArchiveContent = isArchiveURL(param.getCryptedUrl());
        if (isArchiveContent) {
            br.getPage(param.getCryptedUrl());
        } else {
            try {
                /* Check if we have a direct URL --> Host plugin */
                con = br.openGetConnection(param.getCryptedUrl());
                isArchiveContent = isArchiveURL(con.getURL().toString());
                /*
                 * 2020-03-04: E.g. directurls will redirect to subdomain e.g. ia800503.us.archive.org --> Sometimes the only way to differ
                 * between a file or expected html.
                 */
                final String host = Browser.getHost(con.getURL(), true);
                if (!isArchiveContent && (this.looksLikeDownloadableContent(con) || con.getLongContentLength() > br.getLoadLimit() || !host.equals("archive.org"))) {
                    // final DownloadLink fina = this.createDownloadlink(parameter.replace("archive.org", host_decrypted));
                    final DownloadLink dl = new DownloadLink(hostPlugin, null, "archive.org", param.getCryptedUrl(), true);
                    if (this.looksLikeDownloadableContent(con)) {
                        if (con.getCompleteContentLength() > 0) {
                            dl.setVerifiedFileSize(con.getCompleteContentLength());
                        }
                        dl.setFinalFileName(getFileNameFromHeader(con));
                        dl.setAvailable(true);
                    } else {
                        /* 2021-02-05: Either offline or account-only. Assume offline for now. */
                        dl.setAvailable(false);
                    }
                    final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
                    ret.add(dl);
                    return ret;
                } else {
                    final int loadLimit = br.getLoadLimit();
                    try {
                        br.setLoadLimit(-1);
                        br.followConnection();
                    } finally {
                        br.setLoadLimit(loadLimit);
                    }
                }
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }
        /*
         * All "account required" issues usually come with http error 403. See also ArchiveOrg host plugin errorhandling in function
         * "connectionErrorhandling".
         */
        if (br.containsHTML("(?i)>\\s*You must log in to view this content")) {
            /* 2021-02-24: <p class="theatre-title">You must log in to view this content</p> */
            throw new AccountRequiredException();
        } else if (br.containsHTML("(?i)>\\s*Item not available|>\\s*The item is not available due to issues with the item's content")) {
            throw new AccountRequiredException();
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /*
         * Preview (= images of book pages) of books may be available along official download --> Only crawl book preview if no official
         * download is possible.
         */
        final boolean isOfficiallyDownloadable = br.containsHTML("class=\"download-button\"") && !br.containsHTML("class=\"download-lending-message\"");
        final boolean isBookPreviewAvailable = getBookReaderURL(br) != null;
        if (isBookPreviewAvailable) {
            final BookCrawlMode mode = PluginJsonConfig.get(ArchiveOrgConfig.class).getBookCrawlMode();
            if (isOfficiallyDownloadable) {
                if (mode == BookCrawlMode.AUTO) {
                    return crawlDetails(param);
                } else if (mode == BookCrawlMode.ORIGINAL_AND_LOSE_PAGES) {
                    final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
                    ret.addAll(crawlDetails(param));
                    ret.addAll(crawlBook(param, account));
                    return ret;
                } else {
                    return crawlBook(param, account);
                }
            } else {
                return crawlBook(param, account);
            }
        } else if (isArchiveContent) {
            return crawlArchiveContent();
        } else if (StringUtils.containsIgnoreCase(param.getCryptedUrl(), "/details/")) {
            return crawlDetails(param);
        } else {
            return crawlFiles(param);
        }
    }

    private ArrayList<DownloadLink> crawlFiles(final CryptedLink param) throws Exception {
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML(">The item is not available")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!br.containsHTML("\"/download/")) {
            logger.info("Maybe invalid link or nothing there to download: " + param.getCryptedUrl());
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final boolean preferOriginal = PluginJsonConfig.get(ArchiveOrgConfig.class).isFileCrawlerCrawlOnlyOriginalVersions();
        String subfolderPath = new Regex(param.getCryptedUrl(), "https?://[^/]+/download/(.*?)/?$").getMatch(0);
        subfolderPath = Encoding.urlDecode(subfolderPath, false);
        // final String fpName = br.getRegex("<h1>Index of [^<>\"]+/([^<>\"/]+)/?</h1>").getMatch(0);
        final String fpName = subfolderPath;
        String html = br.toString().replaceAll("(\\(\\s*<a.*?</a>\\s*\\))", "");
        final String[] htmls = new Regex(html, "<tr >(.*?)</tr>").getColumn(0);
        final String xmlURLs[] = br.getRegex("<a href\\s*=\\s*\"([^<>\"]+_files\\.xml)\"").getColumn(0);
        String xmlSource = null;
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        if (xmlURLs != null && xmlURLs.length > 0) {
            for (String xmlURL : xmlURLs) {
                final Browser brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                xmlSource = brc.getPage(brc.getURL() + "/" + xmlURL);
                ret.addAll(crawlXML(brc, subfolderPath));
            }
            return ret;
        } else {
            /* Old/harder way */
            for (final String htmlsnippet : htmls) {
                String name = new Regex(htmlsnippet, "<a href=\"([^<>\"]+)\"").getMatch(0);
                final String[] rows = new Regex(htmlsnippet, "<td>(.*?)</td>").getColumn(0);
                if (name == null || rows.length < 3) {
                    /* Skip invalid items */
                    continue;
                }
                String filesize = rows[rows.length - 1];
                if (StringUtils.endsWithCaseInsensitive(name, "_files.xml") || StringUtils.endsWithCaseInsensitive(name, "_meta.sqlite") || StringUtils.endsWithCaseInsensitive(name, "_meta.xml") || StringUtils.endsWithCaseInsensitive(name, "_reviews.xml")) {
                    /* Skip invalid content */
                    continue;
                } else if (xmlSource != null && preferOriginal) {
                    /* Skip non-original content if user only wants original content. */
                    if (!new Regex(xmlSource, "<file name=\"" + Pattern.quote(name) + "\" source=\"original\"").matches()) {
                        continue;
                    }
                }
                if (filesize.equals("-")) {
                    /* Folder --> Goes back into decrypter */
                    final DownloadLink fina = createDownloadlink("https://archive.org/download/" + subfolderPath + "/" + name);
                    ret.add(fina);
                } else {
                    /* File */
                    filesize += "b";
                    final String filename = Encoding.urlDecode(name, false);
                    final DownloadLink fina = createDownloadlink("https://archive.org/download/" + subfolderPath + "/" + name);
                    fina.setDownloadSize(SizeFormatter.getSize(filesize));
                    fina.setAvailable(true);
                    fina.setFinalFileName(filename);
                    if (xmlSource != null) {
                        final String sha1 = new Regex(xmlSource, "<file name=\"" + Pattern.quote(filename) + "\".*?<sha1>([a-f0-9]{40})</sha1>").getMatch(0);
                        if (sha1 != null) {
                            fina.setSha1Hash(sha1);
                        }
                        final String size = new Regex(xmlSource, "<file name=\"" + Pattern.quote(filename) + "\".*?<size>(\\d+)</size>").getMatch(0);
                        if (size != null) {
                            fina.setVerifiedFileSize(Long.parseLong(size));
                        }
                    }
                    fina.setRelativeDownloadFolderPath(subfolderPath);
                    ret.add(fina);
                }
            }
            /* 2020-03-04: Setting packagenames makes no sense anymore as packages will get split by subfolderpath. */
            final FilePackage fp = FilePackage.getInstance();
            if (fpName != null) {
                fp.setName(fpName);
                fp.addLinks(ret);
            }
        }
        return ret;
    }

    private ArrayList<DownloadLink> crawlDetails(final CryptedLink param) throws Exception {
        if (br.containsHTML("id=\"gamepadtext\"")) {
            /* 2020-09-29: Rare case: Download browser emulated games */
            final String subfolderPath = new Regex(param.getCryptedUrl(), "/details/([^/]+)").getMatch(0);
            br.getPage("https://archive.org/download/" + subfolderPath + "/" + subfolderPath + "_files.xml");
            return this.crawlXML(this.br, subfolderPath);
        }
        /** TODO: 2020-09-29: Consider taking the shortcut here to always use that XML straight away (?!) */
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        int page = 2;
        do {
            if (br.containsHTML("This item is only available to logged in Internet Archive users")) {
                ret.add(createDownloadlink(param.getCryptedUrl().replace("/details/", "/download/")));
                break;
            }
            final String showAll = br.getRegex("href=\"(/download/[^\"]*?)\">SHOW ALL").getMatch(0);
            if (showAll != null) {
                ret.add(createDownloadlink(br.getURL(showAll).toString()));
                logger.info("Creating: " + br.getURL(showAll).toString());
                break;
            }
            final String[] details = br.getRegex("<div class=\"item-ia\".*? <a href=\"(/details/[^\"]*?)\" title").getColumn(0);
            if (details == null || details.length == 0) {
                logger.info("Stopping because: Failed to find any results on current page: " + br.getURL());
                break;
            }
            for (final String detail : details) {
                final DownloadLink link = createDownloadlink(br.getURL(detail).toString());
                ret.add(link);
                distribute(link);
            }
            logger.info("Crawled page " + page + " | Results so far: " + ret.size());
            br.getPage("?page=" + (page++));
        } while (!this.isAbort());
        return ret;
    }

    private ArrayList<DownloadLink> crawlArchiveContent() throws Exception {
        /* 2020-09-07: Contents of a .zip/.rar file are also accessible and downloadable separately. */
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String archiveName = new Regex(br.getURL(), ".*/([^/]+)$").getMatch(0);
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(Encoding.htmlDecode(archiveName));
        final String[] htmls = br.getRegex("<tr><td>(.*?)</tr>").getColumn(0);
        for (final String html : htmls) {
            String url = new Regex(html, "(/download/[^\"\\']+)").getMatch(0);
            final String filesizeStr = new Regex(html, "id=\"size\">(\\d+)").getMatch(0);
            if (StringUtils.isEmpty(url)) {
                /* Skip invalid items */
                continue;
            }
            url = "https://archive.org" + url;
            final DownloadLink dl = this.createDownloadlink(url);
            if (filesizeStr != null) {
                dl.setDownloadSize(Long.parseLong(filesizeStr));
            }
            dl.setAvailable(true);
            dl._setFilePackage(fp);
            ret.add(dl);
        }
        return ret;
    }

    private ArrayList<DownloadLink> crawlBook(final CryptedLink param, final Account account) throws Exception {
        /* Crawl all pages of a book */
        final String bookID = new Regex(br.getURL(), "https?://[^/]+/(?:details|download)/([^/]+)").getMatch(0);
        if (bookID == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String bookAjaxURL = getBookReaderURL(br);
        if (bookAjaxURL == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // if (bookAjaxURL.contains(bookID) && !bookAjaxURL.endsWith(bookID)) {
        // /* Correct URL */
        // bookAjaxURL = new Regex(bookAjaxURL, "(.+" + Regex.escape(bookID) + ")").getMatch(0);
        // }
        Boolean isLendingRequired = null;
        boolean crawlerBorrowedBookInThisRun = false;
        long loanedSecondsLeft = 0;
        Map<String, Object> brOptions = null;
        do {
            br.getPage(bookAjaxURL);
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final Map<String, Object> root = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            final Map<String, Object> data = (Map<String, Object>) root.get("data");
            final Map<String, Object> lendingInfo = (Map<String, Object>) data.get("lendingInfo");
            final long daysLeftOnLoan = ((Number) lendingInfo.get("daysLeftOnLoan")).longValue();
            final long secondsLeftOnLoan = ((Number) lendingInfo.get("secondsLeftOnLoan")).longValue();
            if (daysLeftOnLoan > 0) {
                loanedSecondsLeft += daysLeftOnLoan * 24 * 60 * 60;
            }
            if (secondsLeftOnLoan > 0) {
                loanedSecondsLeft += secondsLeftOnLoan;
            }
            brOptions = (Map<String, Object>) data.get("brOptions");
            isLendingRequired = (Boolean) lendingInfo.get("isLendingRequired") == Boolean.TRUE;
            if (crawlerBorrowedBookInThisRun) {
                /* We've borrowed that book in the first run of this loop -> Quit loop */
                break;
            }
            /* Borrow book if necessary */
            if (isLendingRequired == Boolean.TRUE) {
                if (account != null) {
                    if (loanedSecondsLeft > 0) {
                        logger.info("User has already borrowed this book with current account --> Obtaining borrow-cookies");
                        this.hostPlugin.borrowBook(br.cloneBrowser(), account, bookID, true);
                        break;
                    } else {
                        logger.info("Borrowing book --> All pages should be downloadable");
                        /* Try to borrow book if account is available */
                        this.hostPlugin.borrowBook(br.cloneBrowser(), account, bookID, false);
                        /*
                         * Go through this loop again: Refreshes page so we can download all pages and will not only get the preview images
                         */
                        crawlerBorrowedBookInThisRun = true;
                        continue;
                    }
                } else {
                    logger.info("Cannot borrow book --> Only preview pages can be downloaded");
                }
            }
            break;
        } while (true);
        String contentURLFormat = "https://" + this.getHost() + "/details/" + bookID;
        final String bookId = brOptions.get("bookId").toString();
        String title = (String) brOptions.get("bookTitle");
        final String subPrefix = (String) brOptions.get("subPrefix");
        final String idForLinkID;
        final boolean isMultiVolumeBook;
        if (subPrefix != null && !subPrefix.equals(bookId)) {
            /**
             * Books can have multiple volumes. In this case lending the main book will basically lend all volumes alltogether. </br>
             * Problem: Title is the same for all items --> Append this subPrefix to the title to fix that.
             */
            title += " - " + subPrefix;
            idForLinkID = bookId + "_" + subPrefix;
            contentURLFormat += "/" + subPrefix;
            isMultiVolumeBook = true;
        } else {
            idForLinkID = bookId;
            isMultiVolumeBook = false;
        }
        final String pageFormat;
        if (bookAjaxURL.matches(".*/page/n\\d+.*")) {
            pageFormat = "/page/n%d";
        } else {
            pageFormat = "/page/%d";
        }
        contentURLFormat += "%s";
        /*
         * Defines how book pages will be arranged on the archive.org website. User can open single pages faster in browser if we get this
         * right.
         */
        final String bookDisplayMode = new Regex(bookAjaxURL, "/mode/([^/]+)").getMatch(0);
        if (bookDisplayMode != null) {
            contentURLFormat += "/mode/" + bookDisplayMode;
        }
        final List<Object> imagesO = (List<Object>) brOptions.get("data");
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        for (final Object imageO : imagesO) {
            /*
             * Most of all objects will contain an array with 2 items --> Books always have two viewable pages. Exception = First page -->
             * Cover
             */
            final List<Object> pagesO = (List<Object>) imageO;
            for (final Object pageO : pagesO) {
                final Map<String, Object> bookpage = (Map<String, Object>) pageO;
                final int pageNum = ((Number) bookpage.get("leafNum")).intValue();
                final String url = bookpage.get("uri").toString();
                final DownloadLink dl = new DownloadLink(hostPlugin, null, "archive.org", url, true);
                if (pageNum == 1) {
                    /* Special for first page -> URL does not contain path to page for first page. */
                    dl.setContentUrl(String.format(contentURLFormat, ""));
                } else {
                    dl.setContentUrl(String.format(contentURLFormat, String.format(pageFormat, pageNum - 1)));
                }
                dl.setFinalFileName(pageNum + "_ " + title + ".jpg");
                dl.setProperty(ArchiveOrg.PROPERTY_BOOK_ID, bookID);
                if (isMultiVolumeBook) {
                    dl.setProperty(ArchiveOrg.PROPERTY_BOOK_SUB_PREFIX, subPrefix);
                }
                if (isLendingRequired == Boolean.TRUE) {
                    dl.setProperty(ArchiveOrg.PROPERTY_IS_LENDING_REQUIRED, true);
                }
                /**
                 * Mark pages that are not viewable in browser as offline. </br>
                 * If we have borrowed this book, this field will not exist at all.
                 */
                final Boolean viewable = (Boolean) bookpage.get("viewable");
                if (viewable == null || viewable == Boolean.TRUE) {
                    dl.setAvailable(true);
                } else {
                    dl.setAvailable(false);
                    dl.setProperty(ArchiveOrg.PROPERTY_IS_UN_DOWNLOADABLE_BOOK_PREVIEW_PAGE, true);
                }
                if (account == null && isLendingRequired == Boolean.TRUE) {
                    dl.setLinkID(this.getHost() + "://" + idForLinkID + "_preview_" + pageNum);
                } else {
                    dl.setLinkID(this.getHost() + "://" + idForLinkID + "_" + pageNum);
                }
                ret.add(dl);
            }
        }
        if (account != null) {
            account.saveCookies(br.getCookies(br.getHost()), "");
        }
        final FilePackage fp = FilePackage.getInstance();
        if (account == null && isLendingRequired == Boolean.TRUE) {
            fp.setName(title + " - preview");
        } else {
            fp.setName(title);
        }
        fp.addLinks(ret);
        return ret;
    }

    private String getBookReaderURL(final Browser br) {
        return br.getRegex("(?i)\\'([^\\'\"]+BookReaderJSIA\\.php\\?[^\\'\"]+)\\'").getMatch(0);
    }

    private ArrayList<DownloadLink> crawlXML(final Browser br, final String root) {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final boolean preferOriginal = PluginJsonConfig.get(ArchiveOrgConfig.class).isFileCrawlerCrawlOnlyOriginalVersions();
        final boolean crawlArchiveView = PluginJsonConfig.get(ArchiveOrgConfig.class).isFileCrawlerCrawlArchiveView();
        final String[] items = new Regex(br.toString(), "<file\\s*(.*?)\\s*</file>").getColumn(0);
        /*
         * 2020-03-04: Prefer crawling xml if possible as we then get all contents of that folder including contents of subfolders via only
         * one request!
         */
        for (final String item : items) {
            /* <old_version>true</old_version> */
            final boolean isOldVersion = item.contains("old_version");
            final boolean isOriginal = item.contains("source=\"original\"");
            final boolean isMetadata = item.contains("<format>Metadata</format>");
            final boolean isArchiveViewSupported = item.matches("(?i)(?s).*<format>\\s*(RAR|ZIP)\\s*</format>.*");
            String pathWithFilename = new Regex(item, "name=\"([^\"]+)").getMatch(0);
            final String filesizeStr = new Regex(item, "<size>(\\d+)</size>").getMatch(0);
            final String sha1hash = new Regex(item, "<sha1>([a-f0-9]+)</sha1>").getMatch(0);
            if (pathWithFilename == null) {
                continue;
            } else if (isOldVersion || isMetadata) {
                /* Skip old elements and metadata! They are invisible to the user anyways */
                continue;
            } else if (preferOriginal && !isOriginal) {
                /* Skip non-original content if user only wants original content. */
                continue;
            }
            if (Encoding.isHtmlEntityCoded(pathWithFilename)) {
                /* Will sometimes contain "&amp;" */
                pathWithFilename = Encoding.htmlOnlyDecode(pathWithFilename);
            }
            String pathEncoded;
            String pathWithoutFilename = null;
            String filename = null;
            /* Search filename and properly encode content-URL. */
            if (pathWithFilename.contains("/")) {
                final String[] urlParts = pathWithFilename.split("/");
                pathEncoded = "";
                pathWithoutFilename = "";
                int index = 0;
                for (final String urlPart : urlParts) {
                    final boolean isLastSegment = index >= urlParts.length - 1;
                    pathEncoded += URLEncode.encodeURIComponent(urlPart);
                    if (isLastSegment) {
                        filename = urlPart;
                    } else {
                        pathWithoutFilename += urlPart;
                        pathWithoutFilename += "/";
                        pathEncoded += "/";
                    }
                    index++;
                }
            } else {
                pathEncoded = URLEncode.encodeURIComponent(pathWithFilename);
                filename = pathWithFilename;
            }
            final String url = "https://archive.org/download/" + root + "/" + pathEncoded;
            if (dups.add(url)) {
                final DownloadLink downloadURL = createDownloadlink(url);
                downloadURL.setDownloadSize(SizeFormatter.getSize(filesizeStr));
                downloadURL.setAvailable(true);
                downloadURL.setFinalFileName(filename);
                final String thisPath;
                if (pathWithoutFilename != null) {
                    thisPath = root + "/" + pathWithoutFilename;
                } else {
                    thisPath = root;
                }
                downloadURL.setRelativeDownloadFolderPath(thisPath);
                final FilePackage fp = FilePackage.getInstance();
                fp.setName(thisPath);
                downloadURL._setFilePackage(fp);
                if (sha1hash != null) {
                    downloadURL.setSha1Hash(sha1hash);
                }
                ret.add(downloadURL);
                if (crawlArchiveView && isArchiveViewSupported) {
                    final DownloadLink archiveViewURL = createDownloadlink(url + "/");
                    ret.add(archiveViewURL);
                }
            }
        }
        return ret;
    }

    @Override
    protected boolean looksLikeDownloadableContent(final URLConnectionAdapter urlConnection) {
        return hostPlugin.looksLikeDownloadableContent(urlConnection);
    }

    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return ArchiveOrgConfig.class;
    }
}