package org.apache.maven.plugin.doap;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.Properties;

import org.apache.maven.model.Contributor;
import org.apache.maven.model.Developer;
import org.codehaus.plexus.i18n.I18N;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.XMLWriter;
import org.codehaus.plexus.util.xml.XmlWriterUtil;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFReader;
import com.hp.hpl.jena.rdf.model.impl.RDFDefaultErrorHandler;

/**
 * Utility class for DOAP mojo.
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @version $Id$
 * @since 1.0
 */
public class DoapUtil
{
    /** Email regex */
    private static final String EMAIL_REGEX =
        "^[_A-Za-z0-9-]+(\\.[_A-Za-z0-9-]+)*@[A-Za-z0-9]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";

    /** Email pattern */
    private static final Pattern EMAIL_PATTERN = Pattern.compile( EMAIL_REGEX );

    /** Magic number to repeat '=' */
    private static final int REPEAT_EQUALS = 21;

    /** RDF resource attribute */
    protected static final String RDF_RESOURCE = "rdf:resource";

    /** RDF nodeID attribute */
    protected static final String RDF_NODE_ID = "rdf:nodeID";

    /** DoaP Organizations stored by name */
    private static Map<String, DoapUtil.Organization> organizations = new HashMap<String, DoapUtil.Organization>();

    /**
     * Write comments in the DOAP file header
     *
     * @param writer not null
     */
    public static void writeHeader( XMLWriter writer )
    {
        XmlWriterUtil.writeLineBreak( writer );

        XmlWriterUtil.writeCommentLineBreak( writer );
        XmlWriterUtil.writeComment( writer, StringUtils.repeat( "=", REPEAT_EQUALS ) + " - DO NOT EDIT THIS FILE! - "
            + StringUtils.repeat( "=", REPEAT_EQUALS ) );
        XmlWriterUtil.writeCommentLineBreak( writer );
        XmlWriterUtil.writeComment( writer, " " );
        XmlWriterUtil.writeComment( writer, "Any modifications will be overwritten." );
        XmlWriterUtil.writeComment( writer, " " );
        DateFormat dateFormat = DateFormat.getDateTimeInstance( DateFormat.SHORT, DateFormat.SHORT, Locale.US );
        XmlWriterUtil.writeComment( writer, "Generated by Maven Doap Plugin " + getPluginVersion() + " on "
            + dateFormat.format( new Date( System.currentTimeMillis() ) ) );
        XmlWriterUtil.writeComment( writer, "See: http://maven.apache.org/plugins/maven-doap-plugin/" );
        XmlWriterUtil.writeComment( writer, " " );
        XmlWriterUtil.writeCommentLineBreak( writer );

        XmlWriterUtil.writeLineBreak( writer );
    }

    /**
     * @param writer not null
     * @param name not null
     * @param value could be null. In this case, the element is not written.
     * @throws IllegalArgumentException if name is null or empty
     */
    public static void writeElement( XMLWriter writer, String name, String value )
        throws IllegalArgumentException
    {
        if ( StringUtils.isEmpty( name ) )
        {
            throw new IllegalArgumentException( "name should be defined" );
        }

        if ( value != null )
        {
            writer.startElement( name );
            writer.writeText( value );
            writer.endElement();
        }
    }

    /**
     * @param writer not null
     * @param name not null
     * @param lang not null
     * @param value could be null. In this case, the element is not written.
     * @throws IllegalArgumentException if name is null or empty
     */
    public static void writeElement( XMLWriter writer, String name, String value, String lang )
        throws IllegalArgumentException
    {
        if ( StringUtils.isEmpty( lang ) )
        {
            writeElement( writer, name, value );
            return;
        }

        if ( StringUtils.isEmpty( name ) )
        {
            throw new IllegalArgumentException( "name should be defined" );
        }

        if ( value != null )
        {
            writer.startElement( name );
            writer.addAttribute( "xml:lang", lang );
            writer.writeText( value );
            writer.endElement();
        }
    }

    /**
     * @param writer not null
     * @param name not null
     * @param value could be null. In this case, the element is not written.
     * @throws IllegalArgumentException if name is null or empty
     */
    public static void writeRdfResourceElement( XMLWriter writer, String name, String value )
        throws IllegalArgumentException
    {
        if ( StringUtils.isEmpty( name ) )
        {
            throw new IllegalArgumentException( "name should be defined" );
        }

        if ( value != null )
        {
            writer.startElement( name );
            writer.addAttribute( RDF_RESOURCE, value );
            writer.endElement();
        }
    }

    /**
     * @param writer not null
     * @param name not null
     * @param value could be null. In this case, the element is not written.
     * @throws IllegalArgumentException if name is null or empty
     */
    public static void writeRdfNodeIdElement( XMLWriter writer, String name, String value )
        throws IllegalArgumentException
    {
        if ( StringUtils.isEmpty( name ) )
        {
            throw new IllegalArgumentException( "name should be defined" );
        }

        if ( value != null )
        {
            writer.startElement( name );
            writer.addAttribute( RDF_NODE_ID, value );
            writer.endElement();
        }
    }

    /**
     * @param i18n the internationalization component
     * @param developersOrContributors list of <code>{@link Contributor}</code>
     * @return a none null list of <code>{@link Contributor}</code> which have a <code>developer</code> DOAP role.
     */
    public static List<Contributor> getContributorsWithDeveloperRole( I18N i18n,
                                                                      List<Contributor> developersOrContributors )
    {
        return filterContributorsByDoapRoles( i18n, developersOrContributors ).get( "developers" );
    }

    /**
     * @param i18n the internationalization component
     * @param developersOrContributors list of <code>{@link Contributor}</code>
     * @return a none null list of <code>{@link Contributor}</code> which have a <code>documenter</code> DOAP role.
     */
    public static List<Contributor> getContributorsWithDocumenterRole( I18N i18n,
                                                                       List<Contributor> developersOrContributors )
    {
        return filterContributorsByDoapRoles( i18n, developersOrContributors ).get( "documenters" );
    }

    /**
     * @param i18n the internationalization component
     * @param developersOrContributors list of <code>{@link Contributor}</code>
     * @return a none null list of <code>{@link Contributor}</code> which have an <code>helper</code> DOAP role.
     */
    public static List<Contributor> getContributorsWithHelperRole( I18N i18n, List<Contributor> developersOrContributors )
    {
        return filterContributorsByDoapRoles( i18n, developersOrContributors ).get( "helpers" );
    }

    /**
     * @param i18n the internationalization component
     * @param developersOrContributors list of <code>{@link Contributor}</code>
     * @return a none null list of <code>{@link Contributor}</code> which have a <code>maintainer</code> DOAP role.
     */
    public static List<Contributor> getContributorsWithMaintainerRole( I18N i18n,
                                                                       List<Contributor> developersOrContributors )
    {
        return filterContributorsByDoapRoles( i18n, developersOrContributors ).get( "maintainers" );
    }

    /**
     * @param i18n the internationalization component
     * @param developersOrContributors list of <code>{@link Contributor}</code>
     * @return a none null list of <code>{@link Contributor}</code> which have a <code>tester</code> DOAP role.
     */
    public static List<Contributor> getContributorsWithTesterRole( I18N i18n, List<Contributor> developersOrContributors )
    {
        return filterContributorsByDoapRoles( i18n, developersOrContributors ).get( "testers" );
    }

    /**
     * @param i18n the internationalization component
     * @param developersOrContributors list of <code>{@link Contributor}</code>
     * @return a none null list of <code>{@link Contributor}</code> which have a <code>translator</code> DOAP role.
     */
    public static List<Contributor> getContributorsWithTranslatorRole( I18N i18n,
                                                                       List<Contributor> developersOrContributors )
    {
        return filterContributorsByDoapRoles( i18n, developersOrContributors ).get( "translators" );
    }

    /**
     * @param i18n the internationalization component
     * @param developersOrContributors list of <code>{@link Contributor}</code>
     * @return a none null list of <code>{@link Contributor}</code> which have an <code>unknown</code> DOAP role.
     */
    public static List<Contributor> getContributorsWithUnknownRole( I18N i18n,
                                                                    List<Contributor> developersOrContributors )
    {
        return filterContributorsByDoapRoles( i18n, developersOrContributors ).get( "unknowns" );
    }

    /**
     * Utility class for keeping track of DOAP organizations in the DoaP mojo.
     *
     * @author <a href="mailto:t.fliss@gmail.com">Tim Fliss</a>
     * @version $Id$
     * @since 1.1
     */
    public static class Organization
    {
        private String name;

        private String url;

        private List<String> members = new LinkedList<String>();

        public Organization( String name, String url )
        {
            this.name = name;
            this.url = url;
        }

        public void setName( String name )
        {
            this.name = name;
        }

        public String getName()
        {
            return name;
        }

        public void setUrl( String url )
        {
            this.url = url;
        }

        public String getUrl()
        {
            return url;
        }

        public void addMember( String nodeId )
        {
            members.add( nodeId );
        }

        public List<String> getMembers()
        {
            return members;
        }
    }

    /**
     * put an organization from the pom file in the organization list.
     *
     * @param name from the pom file (e.g. Yoyodyne)
     * @param url from the pom file (e.g. http://yoyodyne.example.org/about)
     * @return the existing organization if a duplicate, or a new one.
     */
    public static DoapUtil.Organization addOrganization( String name, String url )
    {
        Organization organization = organizations.get( name );

        if ( organization == null )
        {
            organization = new DoapUtil.Organization( name, url );
        }

        organizations.put( name, organization );

        return organization;
    }

    // unique RDF blank node index scoped internal to the DOAP file
    private static int nodeNumber = 1;

    /**
     * get a unique (within the DoaP file) RDF blank node ID
     *
     * @return the nodeID
     * @see <a href="http://www.w3.org/TR/rdf-syntax-grammar/#section-Syntax-blank-nodes">
     *      http://www.w3.org/TR/rdf-syntax-grammar/#section-Syntax-blank-nodes</a>
     */
    public static String getNodeId()
    {
        return "b" + nodeNumber++;
    }

    /**
     * get the set of Organizations that people are members of
     *
     * @return Map.EntrySet of DoapUtil.Organization
     */
    public static Set<Entry<String, DoapUtil.Organization>> getOrganizations()
    {
        return organizations.entrySet();
    }

    /**
     * Validate the given DOAP file.
     *
     * @param doapFile not null and should exists.
     * @return an empty list if the DOAP file is valid, otherwise a list of errors.
     * @since 1.1
     */
    public static List<String> validate( File doapFile )
    {
        if ( doapFile == null || !doapFile.isFile() )
        {
            throw new IllegalArgumentException( "The DOAP file should exist" );
        }

        Model model = ModelFactory.createDefaultModel();
        RDFReader r = model.getReader( "RDF/XML" );
        r.setProperty( "error-mode", "strict-error" );
        final List<String> errors = new ArrayList<String>();
        r.setErrorHandler( new RDFDefaultErrorHandler()
        {
            @Override
            public void error( Exception e )
            {
                errors.add( e.getMessage() );
            }
        } );

        try
        {
            r.read( model, doapFile.toURI().toURL().toString() );
        }
        catch ( MalformedURLException e )
        {
            // ignored
        }

        return errors;
    }

    /**
     * @param str not null
     * @return <code>true</code> if the str parameter is a valid email, <code>false</code> otherwise.
     * @since 1.1
     */
    public static boolean isValidEmail( String str )
    {
        if ( StringUtils.isEmpty( str ) )
        {
            return false;
        }

        Matcher matcher = EMAIL_PATTERN.matcher( str );
        return matcher.matches();
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * Filter the developers/contributors roles by the keys from {@link I18N#getBundle()}. <br/>
     * I18N roles supported in DOAP, i.e. <code>maintainer</code>, <code>developer</code>, <code>documenter</code>,
     * <code>translator</code>, <code>tester</code>, <code>helper</code>. <br/>
     * <b>Note:</b> Actually, only English keys are used.
     *
     * @param i18n i18n component
     * @param developersOrContributors list of <code>{@link Contributor}</code>
     * @return a none null map with <code>maintainers</code>, <code>developers</code>, <code>documenters</code>,
     *         <code>translators</code>, <code>testers</code>, <code>helpers</code>, <code>unknowns</code> as keys and
     *         list of <code>{@link Contributor}</code> as value.
     */
    private static Map<String, List<Contributor>> filterContributorsByDoapRoles( I18N i18n,
                                                                                 List<Contributor> developersOrContributors )
    {
        Map<String, List<Contributor>> returnMap = new HashMap<String, List<Contributor>>( 7 );
        returnMap.put( "maintainers", new ArrayList<Contributor>() );
        returnMap.put( "developers", new ArrayList<Contributor>() );
        returnMap.put( "documenters", new ArrayList<Contributor>() );
        returnMap.put( "translators", new ArrayList<Contributor>() );
        returnMap.put( "testers", new ArrayList<Contributor>() );
        returnMap.put( "helpers", new ArrayList<Contributor>() );
        returnMap.put( "unknowns", new ArrayList<Contributor>() );

        if ( developersOrContributors == null || developersOrContributors.isEmpty() )
        {
            return returnMap;
        }

        for ( Contributor contributor : developersOrContributors )
        {
            List<String> roles = contributor.getRoles();

            if ( roles != null && roles.size() != 0 )
            {
                for ( String role : roles )
                {
                    role = role.toLowerCase( Locale.ENGLISH );
                    if ( role.contains( getLowerCaseString( i18n, "doap.maintainer" ) ) )
                    {
                        if ( !returnMap.get( "maintainers" ).contains( contributor ) )
                        {
                            returnMap.get( "maintainers" ).add( contributor );
                        }
                    }
                    else if ( role.contains( getLowerCaseString( i18n, "doap.developer" ) ) )
                    {
                        if ( !returnMap.get( "developers" ).contains( contributor ) )
                        {
                            returnMap.get( "developers" ).add( contributor );
                        }
                    }
                    else if ( role.contains( getLowerCaseString( i18n, "doap.documenter" ) ) )
                    {
                        if ( !returnMap.get( "documenters" ).contains( contributor ) )
                        {
                            returnMap.get( "documenters" ).add( contributor );
                        }
                    }
                    else if ( role.contains( getLowerCaseString( i18n, "doap.translator" ) ) )
                    {
                        if ( !returnMap.get( "translators" ).contains( contributor ) )
                        {
                            returnMap.get( "translators" ).add( contributor );
                        }
                    }
                    else if ( role.contains( getLowerCaseString( i18n, "doap.tester" ) ) )
                    {
                        if ( !returnMap.get( "testers" ).contains( contributor ) )
                        {
                            returnMap.get( "testers" ).add( contributor );
                        }
                    }
                    else if ( role.contains( getLowerCaseString( i18n, "doap.helper" ) ) )
                    {
                        if ( !returnMap.get( "helpers" ).contains( contributor ) )
                        {
                            returnMap.get( "helpers" ).add( contributor );
                        }
                    }
                    else if ( role.contains( getLowerCaseString( i18n, "doap.emeritus" ) ) )
                    {
                        // Don't add as developer nor as contributor as the person is no longer involved
                    }
                    else
                    {
                        if ( !returnMap.get( "unknowns" ).contains( contributor ) )
                        {
                            returnMap.get( "unknowns" ).add( contributor );
                        }
                    }
                }
            }
            else
            {
                if ( !returnMap.get( "unknowns" ).contains( contributor ) )
                {
                    returnMap.get( "unknowns" ).add( contributor );
                }
            }
        }

        return returnMap;
    }

    /**
     * @param i18n not null
     * @param key not null
     * @return lower case value for the key in the i18n bundle.
     */
    private static String getLowerCaseString( I18N i18n, String key )
    {
        return i18n.getString( "doap-person", Locale.ENGLISH, key ).toLowerCase( Locale.ENGLISH );
    }

    /**
     * @return the Maven artefact version.
     */
    private static String getPluginVersion()
    {
        Properties pomProperties = new Properties();
        InputStream is = null;
        try
        {
            is =
                DoapUtil.class.getResourceAsStream( "/META-INF/maven/org.apache.maven.plugins/maven-doap-plugin/pom.properties" );
            if ( is == null )
            {
                return "<unknown>";
            }

            pomProperties.load( is );

            return pomProperties.getProperty( "version", "<unknown>" );
        }
        catch ( IOException e )
        {
            return "<unknown>";
        }
        finally
        {
            IOUtil.close( is );
        }
    }
}
