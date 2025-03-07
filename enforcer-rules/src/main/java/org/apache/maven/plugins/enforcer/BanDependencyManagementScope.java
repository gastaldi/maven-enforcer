package org.apache.maven.plugins.enforcer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.enforcer.rule.api.EnforcerRule2;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.InputLocation;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.enforcer.utils.ArtifactMatcher;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;

/**
 * This rule bans all scope values except for {@code import} from dependencies within the dependency management. 
 * There is a configuration option to ignore certain dependencies in this check.
 */
public class BanDependencyManagementScope
    extends AbstractNonCacheableEnforcerRule
    implements EnforcerRule2
{

    /**
     * Specify the dependencies that will be ignored. This can be a list of artifacts in the format
     * <code>groupId[:artifactId][:version][:type][:scope]</code>. Wildcard '*' can be used to in place of specific
     * section (ie group:*:1.0 will match both 'group:artifact:1.0' and 'group:anotherArtifact:1.0'). Version is a
     * string representing standard Maven version range. Empty patterns will be ignored.
     *
     * @see {@link #setExcludes(List)}
     */
    private List<String> excludes = null;

    /**
     * If {@code true} the dependencyManagement from imported dependencyManagement and parent pom's is checked as well,
     * otherwise only the local dependencyManagement defined in the current project's pom.xml.
     */
    private boolean checkEffectivePom = false;

    @Override
    public void execute( EnforcerRuleHelper helper )
        throws EnforcerRuleException
    {
        Log logger = helper.getLog();
        MavenProject project;
        try
        {
            project = (MavenProject) helper.evaluate( "${project}" );
            if ( project == null )
            {
                throw new EnforcerRuleException( "${project} is null" );
            }
            // only evaluate local depMgmt, without taking into account inheritance and interpolation
            DependencyManagement depMgmt = checkEffectivePom ? project.getModel().getDependencyManagement()
                            : project.getOriginalModel().getDependencyManagement();
            if ( depMgmt != null && depMgmt.getDependencies() != null )
            {
                List<Dependency> violatingDependencies  = getViolatingDependencies( logger, depMgmt );
                if ( !violatingDependencies.isEmpty() )
                {
                    String projectId = getProjectId( project );
                    String message = getMessage();
                    StringBuilder buf = new StringBuilder();
                    if ( message == null )
                    {
                        message = "Scope other than 'import' is not allowed in 'dependencyManagement'";
                    }
                    buf.append( message + System.lineSeparator() );
                    for ( Dependency violatingDependency : violatingDependencies )
                    {
                        buf.append( getErrorMessage( projectId, violatingDependency ) );
                    }
                    throw new EnforcerRuleException( buf.toString() );
                }
            }
        }
        catch ( ExpressionEvaluationException e )
        {
            throw new EnforcerRuleException( "Cannot resolve expression: " + e.getCause(), e );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new EnforcerRuleException( "Invalid version range give in excludes " + e.getCause(), e );
        }
    }

    protected List<Dependency> getViolatingDependencies( Log logger, DependencyManagement depMgmt )
        throws InvalidVersionSpecificationException
    {
        final ArtifactMatcher excludesMatcher;
        if ( excludes != null )
        {
            excludesMatcher = new ArtifactMatcher( excludes, Collections.emptyList() );
        }
        else
        {
            excludesMatcher = null;
        }
        List<Dependency> violatingDependencies = new ArrayList<>();
        for ( Dependency dependency : depMgmt.getDependencies() )
        {
            if ( dependency.getScope() != null && !"import".equals( dependency.getScope() ) )
            {
                if ( excludesMatcher != null && excludesMatcher.match( dependency ) )
                {
                    logger.debug( "Skipping excluded dependency " + dependency + " with scope "
                        + dependency.getScope() );
                    continue;
                }
                violatingDependencies.add( dependency );
            }
        }
        return violatingDependencies;
    }

    private static CharSequence getErrorMessage( String projectId, Dependency violatingDependency )
    {
        return "Banned scope '" + violatingDependency.getScope() + "' used on dependency '"
                        + violatingDependency.getManagementKey() + "' @ "
                        + formatLocation( projectId, violatingDependency.getLocation( "" ) )
                        + System.lineSeparator();
    }

    // Get the identifier of the POM in the format <groupId>:<artifactId>:<version>.
    protected static String getProjectId( MavenProject project )
    {
        StringBuilder buffer = new StringBuilder( 128 );

        buffer.append( ( project.getGroupId() != null && project.getGroupId().length() > 0 ) ? project.getGroupId()
                        : "[unknown-group-id]" );
        buffer.append( ':' );
        buffer.append( ( project.getArtifactId() != null && project.getArtifactId().length() > 0 )
                        ? project.getArtifactId()
                        : "[unknown-artifact-id]" );
        buffer.append( ':' );
        buffer.append( ( project.getVersion() != null && project.getVersion().length() > 0 ) ? project.getVersion()
                        : "[unknown-version]" );

        return buffer.toString();
    }

    /**
     * Creates a string with line/column information for problems originating directly from this POM. Inspired by
     * {@code o.a.m.model.building.ModelProblemUtils.formatLocation(...)}.
     *
     * @param projectId the id of the current project's pom.
     * @param location The location which should be formatted, must not be {@code null}.
     * @return The formatted problem location or an empty string if unknown, never {@code null}.
     */
    protected static String formatLocation( String projectId, InputLocation location )
    {
        StringBuilder buffer = new StringBuilder();

        if ( !location.getSource().getModelId().equals( projectId ) )
        {
            buffer.append( location.getSource().getModelId() );

            if ( location.getSource().getLocation().length() > 0 )
            {
                if ( buffer.length() > 0 )
                {
                    buffer.append( ", " );
                }
                buffer.append( location.getSource().getLocation() );
            }
        }
        if ( location.getLineNumber() > 0 )
        {
            if ( buffer.length() > 0 )
            {
                buffer.append( ", " );
            }
            buffer.append( "line " ).append( location.getLineNumber() );
        }
        if ( location.getColumnNumber() > 0 )
        {
            if ( buffer.length() > 0 )
            {
                buffer.append( ", " );
            }
            buffer.append( "column " ).append( location.getColumnNumber() );
        }
        return buffer.toString();
    }

    public void setExcludes( List<String> theExcludes )
    {
        this.excludes = theExcludes;
    }

    public void setCheckEffectivePom( boolean checkEffectivePom )
    {
        this.checkEffectivePom = checkEffectivePom;
    }
}
