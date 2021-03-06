package net.osmand.osm.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import info.bliki.Messages;
import info.bliki.htmlcleaner.ContentToken;
import info.bliki.htmlcleaner.TagNode;
import info.bliki.htmlcleaner.TagToken;
import info.bliki.htmlcleaner.Utils;
import info.bliki.wiki.filter.Encoder;
import info.bliki.wiki.filter.WikipediaParser;
import info.bliki.wiki.filter.WikipediaPreTagParser;
import info.bliki.wiki.model.Configuration;
import info.bliki.wiki.model.ImageFormat;
import info.bliki.wiki.model.WikiModel;
import info.bliki.wiki.tags.HTMLTag;
import info.bliki.wiki.tags.PTag;
import info.bliki.wiki.tags.WPATag;

public class CustomWikiModel extends WikiModel {
		
	private PreparedStatement prep;

	public CustomWikiModel(String imageBaseURL, String linkBaseURL, String folder, PreparedStatement prep) {
		super(imageBaseURL, linkBaseURL);
		this.prep = prep;
	}
	
	@Override
	protected String createImageName(ImageFormat imageFormat) {
		String imageName = imageFormat.getFilename();
				
		if (imageName.endsWith(".svg")) {
			imageName += ".png";
		}
		imageName = Encoder.encodeUrl(imageName);
		if (replaceColon()) {
			imageName = imageName.replace(':', '/');
		}
		return imageName;
	}
	
	@Override
	public void parseInternalImageLink(String imageNamespace,
			String rawImageLink) {
		String imageName = rawImageLink.split("\\|")[0].replaceFirst("File:", "");
		if (imageName.isEmpty()) {
			return;
		}
		String imageHref = getWikiBaseURL();
		ImageFormat imageFormat = ImageFormat.getImageFormat(rawImageLink, imageNamespace);
		String link = imageFormat.getLink();
		if (link != null) {
			if (link.length() == 0) {
				imageHref = "";
			} else {
				String encodedTitle = encodeTitleToUrl(link, true);
				imageHref = imageHref.replace("${title}", encodedTitle);
			}

		} else {
			if (replaceColon()) {
				imageHref = imageHref.replace("${title}", imageNamespace + '/' + imageName);
			} else {
				imageHref = imageHref.replace("${title}", imageNamespace + ':' + imageName);
			}
		}
		String imageSrc = getImageLinkFromDB(imageName);
		if (imageSrc.isEmpty()) {
			return;
		}	
		String type = imageFormat.getType();
		TagToken tag = null;
		if ("thumb".equals(type) || "frame".equals(type)) {
			if (fTagStack.size() > 0) {
				tag = peekNode();
			}
			reduceTokenStack(Configuration.HTML_DIV_OPEN);
		}
		appendInternalImageLink(imageHref, imageSrc, imageFormat);
		if (tag instanceof PTag) {
			pushNode(new PTag());
		}
	}

	public String getImageLinkFromDB(String imageName) {
		String imageSrc = "";
		try {
			prep.setString(1, imageName);
			ResultSet rs = prep.executeQuery();
			while (rs.next()) {
				imageSrc = rs.getString("image_url");
			}
			prep.clearParameters();
		} catch (SQLException e) {}
		return imageSrc;
	}
	
	@Override
	public boolean isValidUriScheme(String uriScheme) {
		return (uriScheme.contains("http") || uriScheme.contains("https") 
				|| uriScheme.contains("ftp") || uriScheme.contains("mailto") 
				|| uriScheme.contains("tel") || uriScheme.contains("geo"));
	}
	
	@Override
    public void appendExternalLink(String uriSchemeName, String link,
            String linkName, boolean withoutSquareBrackets) {
        link = Utils.escapeXml(link, true, false, false);
        if (!uriSchemeName.equals("http") && !uriSchemeName.equals("https") 
        		&& !uriSchemeName.equals("ftp")) {
        	if (uriSchemeName.equals("tel")) {
        		link = link.replaceAll("/", " ").replaceAll("o", "(").replaceAll("c", ")");
        		linkName = link.replaceFirst(uriSchemeName + ":", "");
        	} else {
        		linkName = uriSchemeName.equals("geo") ? "Open on map" : 
            		link.replaceFirst(uriSchemeName + ":", "");
        	}
        }
        TagNode aTagNode = new TagNode("a");
        aTagNode.addAttribute("href", link, true);
        aTagNode.addAttribute("rel", "nofollow", true);
        if (withoutSquareBrackets) {
            aTagNode.addAttribute("class", "external free", true);
            append(aTagNode);
            aTagNode.addChild(new ContentToken(linkName));
        } else {
            String trimmedText = linkName.trim();
            if (trimmedText.length() > 0) {
                pushNode(aTagNode);
                if (linkName.equals(link)
                // protocol-relative URLs also get auto-numbered if there is no
                // real
                // alias
                        || (link.length() >= 2 && link.charAt(0) == '/'
                                && link.charAt(1) == '/' && link.substring(2)
                                .equals(linkName))) {
                    aTagNode.addAttribute("class", "external autonumber", true);
                    aTagNode.addChild(new ContentToken("["
                            + (++fExternalLinksCounter) + "]"));
                } else {
                    aTagNode.addAttribute("class", "external text", true);
                    WikipediaParser.parseRecursive(trimmedText, this, false,
                            true);
                }
                popNode();
            }
        }
    }
	
	@Override
	public void appendInternalLink(String topic, String hashSection,
			String topicDescription, String cssClass, boolean parseRecursive) {
		appendInternalLink(topic, hashSection, topicDescription, cssClass,
				parseRecursive, true);
	}

	protected void appendInternalLink(String topic, String hashSection,
			String topicDescription, String cssClass, boolean parseRecursive,
			boolean topicExists) {
		String hrefLink;
		String description = topicDescription.trim();
		WPATag aTagNode = new WPATag();
		if (topic.length() > 0) {
			String title = Encoder.normaliseTitle(topic, true, ' ', true);
			if (hashSection == null) {
				String pageName = Encoder.normaliseTitle(fPageTitle, true, ' ',
						true);
				// self link?
				if (title.equals(pageName)) {
					HTMLTag selfLink = new HTMLTag("strong");
					selfLink.addAttribute("class", "selflink", false);
					pushNode(selfLink);
					selfLink.addChild(new ContentToken(description));
					popNode();
					return;
				}
			}

			String encodedtopic = encodeTitleToUrl(topic, true);
			if (replaceColon()) {
				encodedtopic = encodedtopic.replace(':', '/');
			}
			hrefLink = getWikiBaseURL().replace("${title}", encodedtopic);
			if (!topicExists) {
				if (cssClass == null) {
					cssClass = "new";
				}
				if (hrefLink.indexOf('?') != -1) {
					hrefLink += "&";
				} else {
					hrefLink += "?";
				}
				hrefLink += "action=edit&redlink=1";
				String redlinkString = Messages.getString(getResourceBundle(),
						Messages.WIKI_TAGS_RED_LINK,
						"${title} (page does not exist)");
				title = redlinkString.replace("${title}", title);
			}
			aTagNode.addAttribute("title", title, true);
		} else {
			// assume, the own topic exists
			if (hashSection != null) {
				hrefLink = "";
				if (description.length() == 0) {
					description = "&#35;" + hashSection; // #....
				}
			} else {
				hrefLink = getWikiBaseURL().replace("${title}", "");
			}
		}

		String href = hrefLink;
		if (topicExists && hashSection != null) {
			href = href + '#' + encodeTitleDotUrl(hashSection, false);
		}
		aTagNode.addAttribute("href", href, true);
		if (cssClass != null) {
			aTagNode.addAttribute("class", cssClass, true);
		}
		aTagNode.addObjectAttribute("wikilink", topic);

		pushNode(aTagNode);
		if (parseRecursive) {
			WikipediaPreTagParser
					.parseRecursive(description, this, false, true);
		} else {
			aTagNode.addChild(new ContentToken(description));
		}
		popNode();
	}
}
