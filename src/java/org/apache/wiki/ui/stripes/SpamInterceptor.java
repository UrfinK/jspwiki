/* 
    JSPWiki - a JSP-based WikiWiki clone.

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.  
 */
package org.apache.wiki.ui.stripes;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import net.sourceforge.stripes.action.Resolution;
import net.sourceforge.stripes.controller.ExecutionContext;
import net.sourceforge.stripes.controller.Interceptor;
import net.sourceforge.stripes.controller.Intercepts;
import net.sourceforge.stripes.controller.LifecycleStage;
import net.sourceforge.stripes.util.bean.NoSuchPropertyException;
import net.sourceforge.stripes.util.bean.PropertyExpression;
import net.sourceforge.stripes.util.bean.PropertyExpressionEvaluation;
import net.sourceforge.stripes.validation.LocalizableError;
import net.sourceforge.stripes.validation.ValidationError;

import org.apache.wiki.WikiEngine;
import org.apache.wiki.action.WikiActionBean;
import org.apache.wiki.api.WikiException;
import org.apache.wiki.content.inspect.*;
import org.apache.wiki.log.Logger;
import org.apache.wiki.log.LoggerFactory;

/**
 * Stripes Interceptor that ensures that SpamFilter algorithms are applied to
 * events annotated with the {@link SpamProtect} annotation. This class
 * processes form parameters generated by the
 * {@link org.apache.wiki.tags.SpamProtectTag} tag. It fires before the
 * {@link LifecycleStage#CustomValidation} stage; that is, after ActionBean and
 * event handler resolution, and just after parameter binding, but before any
 * other custom validation routines have run.
 */
@Intercepts( { LifecycleStage.CustomValidation } )
public class SpamInterceptor implements Interceptor
{

    private static final Logger log = LoggerFactory.getLogger( SpamInterceptor.class );

    /**
     * Validates spam parameters contained in any requests targeting an
     * ActionBean method annotated with the {@link SpamProtect} annotation. This
     * creates a new {@link Inspection} for each ActionBean parameter indicated
     * by the annotation. The {@link InspectionPlan} for the Inspection is
     * obtained by calling
     * {@link SpamInspectionFactory#getInspectionPlan(WikiEngine, java.util.Properties)}.
     * If any of the modifications are determined to be spam, a Stripes
     * {@link ValidationError} is added to the ActionBeanContext.
     * @return always returns {@code null}
     */
    public Resolution intercept( ExecutionContext context ) throws Exception
    {
        // Execute all other interceptors first
        Resolution r = context.proceed();
        if( r != null )
        {
            return r;
        }

        // Get the event handler method
        Method handler = context.getHandler();

        // Find the HandlerInfo method
        WikiActionBean actionBean = (WikiActionBean) context.getActionBean();
        Map<Method, HandlerInfo> eventinfos = HandlerInfo.getHandlerInfoCollection( actionBean.getClass() );
        HandlerInfo eventInfo = eventinfos.get( handler );
        if( eventInfo == null )
        {
            String message = "Event handler method " + actionBean.getClass().getName() +
                             "#" + handler.getName() +
                             " does not have an associated HandlerInfo object. This should not happen.";
            log.error( message );
            throw new WikiException( message );
        }

        // Is the target handler protected by a @SpamProtect annotation?
        if ( !eventInfo.isSpamProtected() )
        {
            return null;
        }

        // Retrieve all of the bean fields named in the @SpamProtect annotation
        WikiActionBeanContext actionBeanContext = actionBean.getContext();
        WikiEngine engine = actionBeanContext.getEngine();
        InspectionPlan plan = SpamInspectionFactory.getInspectionPlan( engine, engine.getWikiProperties() );
        Map<String, Object> fieldValues = getBeanProperties( actionBean, eventInfo.getSpamProtectedFields() );

        // Create an Inspection for analyzing each field
        Inspection inspection = new Inspection( actionBeanContext, plan );
        float spamScoreLimit = SpamInspectionFactory.defaultSpamLimit( engine );
        SpamInspectionFactory.setSpamLimit( inspection, spamScoreLimit );

        // Let's get to it!
        for( Map.Entry<String, Object> entry : fieldValues.entrySet() )
        {
            String name = entry.getKey();
            String value = entry.getValue().toString();
            Change change;
            if( "page".equals( name ) )
            {
                change = Change.getPageChange( actionBeanContext, value );
            }
            else
            {
                change = Change.getChange( value );
            }

            // Run the Inspection
            inspection.inspect( value, change );
            float spamScore = inspection.getScore( Topic.SPAM );

            // If it's spam, add a validation error for the field
            if( spamScore <= spamScoreLimit )
            {
                ValidationError error = new LocalizableError( "message.spam" );
                actionBeanContext.getValidationErrors().add( name, error );
            }
        }

        return null;
    }

    /**
     * Introspects an ActionBean and returns the value for one or more supplied
     * properties. Any properties not found will be cheerfully ignored.
     * 
     * @param actionBean the actionBean to inspect
     * @param beanProperties the bean properties to examine
     * @return the values if successfully evaluated, or <code>null</code> if not
     *         (or not set)
     */
    protected static Map<String, Object> getBeanProperties( WikiActionBean actionBean, String[] beanProperties )
    {
        Map<String, Object> map = new HashMap<String, Object>();
        for( String beanProperty : beanProperties )
        {
            try
            {
                PropertyExpression propExpression = PropertyExpression.getExpression( beanProperty );
                PropertyExpressionEvaluation evaluation = new PropertyExpressionEvaluation( propExpression, actionBean );
                Object value = evaluation.getValue();
                {
                    if ( value == null )
                    {
                        value = "";
                    }
                    map.put( beanProperty, value );
                }
            }
            catch( NoSuchPropertyException e )
            {
                // Ignore any missing properties
            }
        }
        return map;
    }

}