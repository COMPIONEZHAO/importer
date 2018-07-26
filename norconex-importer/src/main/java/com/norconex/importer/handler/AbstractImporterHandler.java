/* Copyright 2010-2018 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.importer.handler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.tika.utils.CharsetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.commons.lang.config.IXMLConfigurable;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.xml.XML;
import com.norconex.importer.doc.ImporterMetadata;
import com.norconex.importer.util.CharsetUtil;

/**
 * Base class for handlers applying only to certain type of documents
 * by providing a way to restrict applicable documents based on
 * a metadata field value, where the value matches a regular expression. For
 * instance, to apply a handler only to text documents, you can use the
 * following:
 *
 * <pre>
 *   myHandler.setRestriction("document.contentType", "^text/.*$");
 * </pre>
 *
 * <h3>XML configuration usage:</h3>
 * Subclasses inherit this {@link IXMLConfigurable} configuration:
 *
 * <pre>
 *  &lt;restrictTo caseSensitive="[false|true]"
 *          field="(name of metadata field name to match)"&gt;
 *      (regular expression of value to match)
 *  &lt;/restrictTo&gt;
 *  &lt;!-- multiple "restrictTo" tags allowed (only one needs to match) --&gt;
 * </pre>
 * <p>
 * Subclasses <b>must</b> test if a document is accepted using the
 * {@link #isApplicable(String, ImporterMetadata, boolean)} method.
 * </p>
 * <p>
 * Subclasses can safely be used as either pre-parse or post-parse handlers.
 * </p>
 * @author Pascal Essiembre
 * @since 2.0.0
 */
public abstract class AbstractImporterHandler implements IXMLConfigurable {

    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractImporterHandler.class);

    private final List<PropertyMatcher> restrictions = new ArrayList<>();

    public AbstractImporterHandler() {
        super();
    }

    /**
     * Adds a restriction this handler should be restricted to.
     * @param field metadata property/field
     * @param regex regular expression
     * @param caseSensitive whether regular expression should be case sensitive
     */
    public synchronized void addRestriction(
            String field, String regex, boolean caseSensitive) {
        restrictions.add(new PropertyMatcher(field, regex, caseSensitive));
    }

    /**
     * Adds one or more restrictions this handler should be restricted to.
     * @param restriction the restriction
     * @since 2.4.0
     */
    public synchronized void addRestriction(PropertyMatcher... restriction) {
        for (PropertyMatcher propertyMatcher : restriction) {
            restrictions.add(propertyMatcher);
        }
    }
    /**
     * Adds restrictions this handler should be restricted to.
     * @param restrictions the restrictions
     * @since 2.4.0
     */
    public synchronized void addRestrictions(
            List<PropertyMatcher> restrictions) {
        if (restrictions != null) {
            for (PropertyMatcher propertyMatcher : restrictions) {
                this.restrictions.add(propertyMatcher);
            }
        }
    }

    /**
     * Removes all restrictions on a given field.
     * @param field the field to remove restrictions on
     * @return how many elements were removed
     * @since 2.4.0
     */
    public synchronized  int removeRestriction(String field) {
        Iterator<PropertyMatcher> it = restrictions.iterator();
        int count = 0;
        while (it.hasNext()) {
            PropertyMatcher r = it.next();
            if (r.isCaseSensitive() && r.getKey().equals(field)
                    || !r.isCaseSensitive()
                            && r.getKey().equalsIgnoreCase(field)) {
                it.remove();
                count++;
            }
        }
        return count;
    }

    /**
     * Removes a restriction.
     * @param restriction the restriction to remove
     * @return <code>true</code> if this handler contained the restriction
     * @since 2.4.0
     */
    public synchronized boolean removeRestriction(PropertyMatcher restriction) {
        return restrictions.remove(restriction);
    }

    /**
     * Clears all restrictions.
     * @since 2.4.0
     */
    public synchronized void clearRestrictions() {
        restrictions.clear();
    }

    /**
     * Gets all restrictions
     * @return the restrictions
     * @since 2.4.0
     */
    public List<PropertyMatcher> getRestrictions() {
        return restrictions;
    }

    /**
     * Class to invoke by subclasses to find out if this handler should be
     * rejected or not based on the metadata restriction provided.
     * @param reference document reference
     * @param metadata document metadata.
     * @param parsed if the document was parsed (i.e. imported) already
     * @return <code>true</code> if this handler is applicable to the document
     */
    protected final boolean isApplicable(
            String reference, ImporterMetadata metadata, boolean parsed) {
        if (restrictions.isEmpty()) {
            return true;
        }
        for (PropertyMatcher restriction : restrictions) {
            if (restriction.matches(metadata)) {
                return true;
            }
        }
        LOG.debug("{} handler does not apply to: {} (parsed={}).",
                getClass(), reference, parsed);
        return false;
    }

    /**
     * Convenience method for handlers that need to detect an input encoding
     * if the explicitly provided encoding is blank.  Detection is only
     * attempted if parsing has not occurred (since parsing converts everything
     * to UTF-8 already).
     * @param charset the character encoding to test if blank
     * @param reference the reference of the document to detect charset on
     * @param document the document to detect charset on
     * @param metadata the document metadata to check for declared encoding
     * @param parsed whether the document has already been parsed or not.
     * @return detected and clean encoding.
     */
    protected final String detectCharsetIfBlank(
            String charset,
            String reference,
            InputStream document,
            ImporterMetadata metadata,
            boolean parsed) {
        if (parsed) {
            LOG.debug("Document already parsed, assuming UTF-8 charset: {}",
                    reference);
            return StandardCharsets.UTF_8.toString();
        }

        String detectedCharset = charset;
        if (StringUtils.isNotBlank(detectedCharset)) {
            return CharsetUtils.clean(detectedCharset);
        } else {
            String declaredEncoding = null;
            if (metadata != null) {
                declaredEncoding = metadata.getString(
                        ImporterMetadata.DOC_CONTENT_ENCODING);
            }
            try {
                detectedCharset = CharsetUtil.detectCharset(
                        document, declaredEncoding);
            } catch (IOException e) {
                detectedCharset = StandardCharsets.UTF_8.toString();
                LOG.debug("Problem detecting encoding for: " + reference, e);
            }
        }
        if (StringUtils.isBlank(detectedCharset)) {
            detectedCharset = StandardCharsets.UTF_8.toString();
            LOG.debug("Cannot detect source encoding. UTF-8 will be "
                    + "assumed for {}: ", reference);
        } else {
            detectedCharset = CharsetUtils.clean(detectedCharset);
        }
        return detectedCharset;
    }

    @Override
    public final void loadFromXML(XML xml) {
        loadHandlerFromXML(xml);
        List<XML> nodes = xml.getXMLList("restrictTo");

        if (!nodes.isEmpty()) {
            restrictions.clear();
            for (XML node : nodes) {
                addRestriction(
                        node.getString("@field"),
                        node.getString("."),
                        node.getBoolean("@caseSensitive", false));
            }
        }
    }
    /**
     * Loads configuration settings specific to the implementing class.
     * @param xml XML configuration
     */
    protected abstract void loadHandlerFromXML(XML xml);

    @Override
    public void saveToXML(XML xml) {
        for (PropertyMatcher restriction : restrictions) {
            XML rxml = xml.addElement("restrictTo", restriction.getRegex());
            rxml.setAttribute("field", restriction.getKey());
            rxml.setAttribute("caseSensitive", restriction.isCaseSensitive());
        }
        saveHandlerToXML(xml);
    }

    /**
     * Saves configuration settings specific to the implementing class.
     * @param xml the XML
     */
    protected abstract void saveHandlerToXML(XML xml);

    @Override
    public boolean equals(final Object other) {
        return EqualsBuilder.reflectionEquals(this, other);
    }
    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }
    @Override
    public String toString() {
        return new ReflectionToStringBuilder(this,
                ToStringStyle.SHORT_PREFIX_STYLE).toString();
    }
}
