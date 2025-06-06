/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.util;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.rometools.modules.itunes.EntryInformation;
import com.rometools.modules.itunes.EntryInformationImpl;
import com.rometools.modules.itunes.types.Duration;
import com.rometools.rome.feed.module.DCModule;
import com.rometools.rome.feed.module.DCModuleImpl;
import com.rometools.rome.feed.module.Module;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEnclosureImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.feed.synd.SyndImage;
import com.rometools.rome.feed.synd.SyndImageImpl;
import com.rometools.rome.feed.synd.SyndPerson;
import com.rometools.rome.feed.synd.SyndPersonImpl;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DCDate;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.CommunityService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.core.I18nUtil;
import org.dspace.discovery.IndexableObject;
import org.dspace.discovery.indexobject.IndexableCollection;
import org.dspace.discovery.indexobject.IndexableCommunity;
import org.dspace.discovery.indexobject.IndexableItem;
import org.dspace.handle.factory.HandleServiceFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.w3c.dom.Document;

/**
 * Invoke ROME library to assemble a generic model of a syndication
 * for the given list of Items and scope.  Consults configuration for the
 * metadata bindings to feed elements.  Uses ROME's output drivers to
 * return any of the implemented formats, e.g. RSS 1.0, RSS 2.0, ATOM 1.0.
 *
 * The feed generator and OpenSearch call on this class so feed contents are
 * uniform for both.
 *
 * @author Larry Stone
 */
public class SyndicationFeed {
    protected final Logger log = org.apache.logging.log4j.LogManager.getLogger(SyndicationFeed.class);


    /**
     * i18n key values
     */
    public static final String MSG_UNTITLED = "notitle";
    public static final String MSG_LOGO_TITLE = "logo.title";
    public static final String MSG_FEED_TITLE = "feed.title";
    public static final String MSG_FEED_DESCRIPTION = "general-feed.description";
    public static final String MSG_METADATA = "metadata.";

    // default DC fields for entry
    protected String defaultTitleField = "dc.title";
    protected String defaultDescriptionField = "dc.description";
    protected String defaultAuthorField = "dc.contributor.author";
    protected String defaultDateField = "dc.date.issued";
    private static final String[] defaultDescriptionFields =
        new String[] {
            "dc.description.abstract",
            "dc.description",
            "dc.title.alternative",
            "dc.title"
        };
    protected String defaultExternalMedia = "dc.source.uri";

    private static final ConfigurationService configurationService =
        DSpaceServicesFactory.getInstance().getConfigurationService();

    // metadata field for Item title in entry:
    protected String titleField =
        configurationService.getProperty("webui.feed.item.title", defaultTitleField);

    // metadata field for Item publication date in entry:
    protected String dateField =
        configurationService.getProperty("webui.feed.item.date", defaultDateField);

    // metadata field for Item description in entry:
    private static final String descriptionFields[] =
        DSpaceServicesFactory.getInstance().getConfigurationService()
                             .getArrayProperty("webui.feed.item.description", defaultDescriptionFields);

    protected String authorField =
        configurationService.getProperty("webui.feed.item.author", defaultAuthorField);

    // metadata field for Podcast external media source url
    protected String externalSourceField =
        configurationService.getProperty("webui.feed.podcast.sourceuri", defaultExternalMedia);

    // metadata field for Item dc:creator field in entry's DCModule (no default)
    protected String dcCreatorField = configurationService.getProperty("webui.feed.item.dc.creator");

    // metadata field for Item dc:date field in entry's DCModule (no default)
    protected String dcDateField = configurationService.getProperty("webui.feed.item.dc.date");

    // metadata field for Item dc:author field in entry's DCModule (no default)
    protected String dcDescriptionField = configurationService.getProperty("webui.feed.item.dc.description");

    // List of available mimetypes that we'll add to podcast feed. Multiple values separated by commas
    protected String[] podcastableMIMETypes =
        configurationService.getArrayProperty("webui.feed.podcast.mimetypes", new String[] {"audio/x-mpeg"});

    // the feed object we are building
    protected SyndFeed feed = null;

    protected HttpServletRequest request = null;

    protected CollectionService collectionService;
    protected CommunityService communityService;
    protected ItemService itemService;

    public SyndicationFeed() {
        feed = new SyndFeedImpl();
        ContentServiceFactory contentServiceFactory = ContentServiceFactory.getInstance();
        itemService = contentServiceFactory.getItemService();
        collectionService = contentServiceFactory.getCollectionService();
        communityService = contentServiceFactory.getCommunityService();
    }

    /**
     * Fills in the feed and entry-level metadata from DSpace objects.
     *
     * @param request request
     * @param context context
     * @param dso     the scope
     * @param items   array of objects
     */
    public void populate(HttpServletRequest request, Context context, IndexableObject dso,
                         List<IndexableObject> items) {
        String logoURL = null;
        String objectURL = null;
        String defaultTitle = null;
        boolean podcastFeed = false;
        this.request = request;

        Map<String, String> labels = getLabels();

        // dso is null for the whole site, or a search without scope
        if (dso == null) {
            defaultTitle = configurationService.getProperty("dspace.name");
            defaultDescriptionField = localize(labels, MSG_FEED_DESCRIPTION);
            objectURL = resolveURL(request, null);
        } else {
            Bitstream logo = null;
            if (dso instanceof IndexableCollection) {
                Collection col = ((IndexableCollection) dso).getIndexedObject();
                defaultTitle = col.getName();
                defaultDescriptionField = collectionService.getMetadataFirstValue(col,
                        CollectionService.MD_SHORT_DESCRIPTION, Item.ANY);
                logo = col.getLogo();
                String cols = configurationService.getProperty("webui.feed.podcast.collections");
                if (cols != null && cols.length() > 1 && cols.contains(col.getHandle())) {
                    podcastFeed = true;
                }
                objectURL = resolveURL(request, col);
            } else if (dso instanceof IndexableCommunity) {
                Community comm = ((IndexableCommunity) dso).getIndexedObject();
                defaultTitle = comm.getName();
                defaultDescriptionField = communityService.getMetadataFirstValue(comm,
                        CommunityService.MD_SHORT_DESCRIPTION, Item.ANY);
                logo = comm.getLogo();
                String comms = configurationService.getProperty("webui.feed.podcast.communities");
                if (comms != null && comms.length() > 1 && comms.contains(comm.getHandle())) {
                    podcastFeed = true;
                }
                objectURL = resolveURL(request, comm);
            }

            if (logo != null) {
                logoURL = urlOfBitstream(request, logo);
            }
        }
        feed.setTitle(labels.containsKey(MSG_FEED_TITLE) ?
                          localize(labels, MSG_FEED_TITLE) : defaultTitle);

        if (defaultDescriptionField == null || defaultDescriptionField == "") {
            defaultDescriptionField = I18nUtil.getMessage("org.dspace.app.util.SyndicationFeed.no-description");
        }

        feed.setDescription(defaultDescriptionField);
        feed.setLink(objectURL);
        feed.setPublishedDate(java.util.Date.from(Instant.now()));
        feed.setUri(objectURL);

        // add logo if we found one:
        if (logoURL != null) {
            // we use the path to the logo for this, the logo itself cannot
            // be contained in the rdf. Not all RSS-viewers show this logo.
            SyndImage image = new SyndImageImpl();
            image.setLink(objectURL);
            if (StringUtils.isNotBlank(feed.getTitle())) {
                image.setTitle(feed.getTitle());
            } else {
                image.setTitle(localize(labels, MSG_LOGO_TITLE));
            }
            image.setUrl(logoURL);
            feed.setImage(image);
        }

        // add entries for items
        if (items != null) {
            List<SyndEntry> entries = new ArrayList<>();
            for (IndexableObject idxObj : items) {
                if (!(idxObj instanceof IndexableItem)) {
                    continue;
                }
                Item item = ((IndexableItem) idxObj).getIndexedObject();
                boolean hasDate = false;
                SyndEntry entry = new SyndEntryImpl();
                entries.add(entry);

                String entryURL = resolveURL(request, item);
                entry.setLink(entryURL);
                entry.setUri(entryURL);

                String title = getOneDC(item, titleField);
                entry.setTitle(title == null ? localize(labels, MSG_UNTITLED) : title);

                // "published" date -- should be dc.date.issued
                String pubDateString = getOneDC(item, dateField);
                if (pubDateString != null) {
                    ZonedDateTime pubDate = new DCDate(pubDateString).toDate();
                    // If date string could not be parsed as a date, then pubDate will be null
                    if (pubDate != null) {
                        entry.setPublishedDate(java.util.Date.from(pubDate.toInstant()));
                        hasDate = true;
                    } else {
                        log.warn("Date field {} for item {} could not be parsed: {}", dateField, item.getID(),
                                 pubDateString);
                    }
                }
                // date of last change to Item
                entry.setUpdatedDate(java.util.Date.from(item.getLastModified()));

                StringBuilder db = new StringBuilder();
                for (String df : descriptionFields) {
                    // Special Case: "(date)" in field name means render as date
                    boolean isDate = df.indexOf("(date)") > 0;
                    if (isDate) {
                        df = df.replaceAll("\\(date\\)", "");
                    }

                    List<MetadataValue> dcv = itemService.getMetadataByMetadataString(item, df);
                    if (dcv.size() > 0) {
                        String fieldLabel = labels.get(MSG_METADATA + df);
                        if (fieldLabel != null && fieldLabel.length() > 0) {
                            db.append(fieldLabel).append(": ");
                        }
                        boolean first = true;
                        for (MetadataValue v : dcv) {
                            if (first) {
                                first = false;
                            } else {
                                db.append("; ");
                            }
                            db.append(isDate ? new DCDate(v.getValue()).toString() : v.getValue());
                        }
                        db.append("\n");
                    }
                }
                if (db.length() > 0) {
                    SyndContent desc = new SyndContentImpl();
                    desc.setType("text/plain");
                    desc.setValue(db.toString());
                    entry.setDescription(desc);
                }

                // This gets the authors into an ATOM feed
                List<MetadataValue> authors = itemService.getMetadataByMetadataString(item, authorField);
                if (authors.size() > 0) {
                    List<SyndPerson> creators = new ArrayList<>();
                    for (MetadataValue author : authors) {
                        SyndPerson sp = new SyndPersonImpl();
                        sp.setName(author.getValue());
                        creators.add(sp);
                    }
                    entry.setAuthors(creators);
                }

                // only add DC module if any DC fields are configured
                if (dcCreatorField != null || dcDateField != null ||
                    dcDescriptionField != null) {
                    DCModule dc = new DCModuleImpl();
                    if (dcCreatorField != null) {
                        List<MetadataValue> dcAuthors = itemService
                                .getMetadataByMetadataString(item, dcCreatorField);
                        if (dcAuthors.size() > 0) {
                            List<String> creators = new ArrayList<>();
                            for (MetadataValue author : dcAuthors) {
                                creators.add(author.getValue());
                            }
                            dc.setCreators(creators);
                        }
                    }
                    if (dcDateField != null && !hasDate) {
                        List<MetadataValue> v = itemService.getMetadataByMetadataString(item, dcDateField);
                        if (v.size() > 0) {
                            ZonedDateTime dateTime = (new DCDate(v.get(0).getValue())).toDate();
                            dc.setDate(java.util.Date.from(dateTime.toInstant()));
                        }
                    }
                    if (dcDescriptionField != null) {
                        List<MetadataValue> v = itemService
                                .getMetadataByMetadataString(item, dcDescriptionField);
                        if (v.size() > 0) {
                            StringBuilder descs = new StringBuilder();
                            for (MetadataValue d : v) {
                                if (descs.length() > 0) {
                                    descs.append("\n\n");
                                }
                                descs.append(d.getValue());
                            }
                            dc.setDescription(descs.toString());
                        }
                    }
                    entry.getModules().add(dc);
                }

                //iTunes Podcast Support - START
                if (podcastFeed) {
                    // Add enclosure(s)
                    List<SyndEnclosure> enclosures = new ArrayList();
                    try {
                        List<Bundle> bunds = itemService.getBundles(item, "ORIGINAL");
                        if (bunds.get(0) != null) {
                            List<Bitstream> bits = bunds.get(0).getBitstreams();
                            for (Bitstream bit : bits) {
                                String mime = bit.getFormat(context).getMIMEType();
                                if (ArrayUtils.contains(podcastableMIMETypes, mime)) {
                                    SyndEnclosure enc = new SyndEnclosureImpl();
                                    enc.setType(bit.getFormat(context).getMIMEType());
                                    enc.setLength(bit.getSizeBytes());
                                    enc.setUrl(urlOfBitstream(request, bit));
                                    enclosures.add(enc);

                                }
                            }
                        }
                        //Also try to add an external value from dc.identifier.other
                        // We are assuming that if this is set, then it is a media file
                        List<MetadataValue> externalMedia = itemService
                            .getMetadataByMetadataString(item, externalSourceField);
                        if (externalMedia.size() > 0) {
                            for (MetadataValue anExternalMedia : externalMedia) {
                                SyndEnclosure enc = new SyndEnclosureImpl();
                                enc.setType(
                                    "audio/x-mpeg");        //We can't determine MIME of external file, so just
                                // picking one.
                                enc.setLength(1);
                                enc.setUrl(anExternalMedia.getValue());
                                enclosures.add(enc);
                            }
                        }

                    } catch (SQLException e) {
                        System.out.println(e.getMessage());
                    }
                    entry.setEnclosures(enclosures);

                    // Get iTunes specific fields: author, subtitle, summary, duration, keywords
                    EntryInformation itunes = new EntryInformationImpl();

                    String author = getOneDC(item, authorField);
                    if (author != null && author.length() > 0) {
                        itunes.setAuthor(author);                               // <itunes:author>
                    }

                    itunes.setSubtitle(title == null ? localize(labels, MSG_UNTITLED) : title); // <itunes:subtitle>

                    if (db.length() > 0) {
                        itunes.setSummary(db.toString());                       // <itunes:summary>
                    }

                    String extent = getOneDC(item,
                                             "dc.format.extent");         // assumed that user will enter this field
                    // with length of song in seconds
                    if (extent != null && extent.length() > 0) {
                        extent = extent.split(" ")[0];
                        long duration = Long.parseLong(extent);
                        itunes.setDuration(new Duration(duration));             // <itunes:duration>
                    }

                    String subject = getOneDC(item, "dc.subject");
                    if (subject != null && subject.length() > 0) {
                        String[] subjects = new String[1];
                        subjects[0] = subject;
                        itunes.setKeywords(subjects);                           // <itunes:keywords>
                    }

                    entry.getModules().add(itunes);
                }
            }
            feed.setEntries(entries);
        }
    }

    /**
     * Sets the feed type for XML delivery, e.g. "rss_1.0", "atom_1.0"
     * Must match one of ROME's configured generators, see rome.properties
     * (currently rss_1.0, rss_2.0, atom_1.0, atom_0.3)
     *
     * @param feedType feed type
     */
    public void setType(String feedType) {
        feed.setFeedType(feedType);
        // XXX FIXME: workaround ROME 1.0 bug, it puts invalid image element in rss1.0
        if ("rss_1.0".equals(feedType)) {
            feed.setImage(null);
        }
    }

    /**
     * @return the feed we built as DOM Document
     * @throws FeedException if feed error
     */
    public Document outputW3CDom()
        throws FeedException {
        try {
            SyndFeedOutput feedWriter = new SyndFeedOutput();
            return feedWriter.outputW3CDom(feed);
        } catch (FeedException e) {
            log.error(e);
            throw e;
        }
    }

    /**
     * @return the feed we built as serialized XML string
     * @throws FeedException if feed error
     */
    public String outputString()
        throws FeedException {
        SyndFeedOutput feedWriter = new SyndFeedOutput();
        return feedWriter.outputString(feed);
    }

    /**
     * send the output to designated Writer
     *
     * @param writer Writer
     * @throws FeedException if feed error
     * @throws IOException   if IO error
     */
    public void output(java.io.Writer writer)
        throws FeedException, IOException {
        SyndFeedOutput feedWriter = new SyndFeedOutput();
        feedWriter.output(feed, writer);
    }

    /**
     * Add a ROME plugin module (e.g. for OpenSearch) at the feed level.
     *
     * @param m module
     */
    public void addModule(Module m) {
        feed.getModules().add(m);
    }

    // utility to get config property with default value when not set.
    protected static String getDefaultedConfiguration(String key, String dfl) {
        String result = configurationService.getProperty(key);
        return (result == null) ? dfl : result;
    }

    // returns absolute URL to download content of bitstream (which might not belong to any Item)
    protected String urlOfBitstream(HttpServletRequest request, Bitstream logo) {
        String name = logo.getName();
        return resolveURL(request, null) +
            "/bitstreams/" + logo.getID() + "/download";
    }

    protected String baseURL = null;  // cache the result for null

    /**
     * Return a url to the DSpace object, either use the official
     * handle for the item or build a url based upon the current server.
     *
     * If the dspaceobject is null then a local url to the repository is generated.
     *
     * @param request current servlet request
     * @param dso     The object to reference, null if to the repository.
     * @return URL
     */
    protected String resolveURL(HttpServletRequest request, DSpaceObject dso) {
        // If no object given then just link to the whole repository,
        // since no official handle exists so we have to use local resolution.
        if (dso == null) {
            if (baseURL == null) {
                if (request == null) {
                    baseURL = configurationService.getProperty("dspace.ui.url");
                } else {
                    baseURL = configurationService.getProperty("dspace.ui.url");
                    baseURL += request.getContextPath();
                }
            }
            return baseURL;
        } else if (configurationService.getBooleanProperty("webui.feed.localresolve")) {
            // return a link to handle in repository
            return resolveURL(request, null) + "/handle/" + dso.getHandle();
        } else {
            // link to the Handle server or other persistent URL source
            return HandleServiceFactory.getInstance().getHandleService().getCanonicalForm(dso.getHandle());
        }
    }

    // retrieve text for localization key, or mark untranslated
    protected String localize(Map<String, String> labels, String s) {
        return labels.containsKey(s) ? labels.get(s) : ("Untranslated:" + s);
    }

    // spoonful of syntactic sugar when we only need first value
    protected String getOneDC(Item item, String field) {
        List<MetadataValue> dcv = itemService.getMetadataByMetadataString(item, field);
        return (dcv.size() > 0) ? dcv.get(0).getValue() : null;
    }

    /**
     * Internal method to get labels for the returned document
     */
    private Map<String, String> getLabels() {
        // TODO: get strings from translation file or configuration
        Map<String, String> labelMap = new HashMap<>();
        labelMap.put(SyndicationFeed.MSG_UNTITLED, "notitle");
        labelMap.put(SyndicationFeed.MSG_LOGO_TITLE, "logo.title");
        labelMap.put(SyndicationFeed.MSG_FEED_DESCRIPTION, "general-feed.description");
        for (String selector : descriptionFields) {
            labelMap.put("metadata." + selector, selector);
        }
        return labelMap;
    }
}
