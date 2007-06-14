/*
 * Copyright 2004-2005 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */ 
package org.codehaus.groovy.grails.plugins.web;
                                                 
import org.codehaus.groovy.grails.support.*
import org.codehaus.groovy.grails.plugins.support.GrailsPluginUtils
import org.codehaus.groovy.grails.web.errors.GrailsExceptionResolver;
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes;
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsUrlHandlerMapping;
import org.codehaus.groovy.grails.web.servlet.mvc.SimpleGrailsController;
import org.codehaus.groovy.grails.web.servlet.view.GrailsViewResolver;
import org.codehaus.groovy.grails.beans.factory.UrlMappingFactoryBean;
import org.springframework.aop.target.HotSwappableTargetSource;
import org.springframework.aop.framework.ProxyFactoryBean;
import org.codehaus.groovy.grails.validation.ConstrainedPropertyBuilder

import org.springframework.web.multipart.commons.CommonsMultipartResolver;
import org.codehaus.groovy.grails.commons.ControllerArtefactHandler;
import org.codehaus.groovy.grails.commons.spring.*
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.MethodInvokingFactoryBean;
import org.codehaus.groovy.grails.commons.GrailsClassUtils as GCU
import org.codehaus.groovy.grails.commons.GrailsResourceUtils as GRU
import org.codehaus.groovy.grails.commons.GrailsMetaClassUtils as GMCU     
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.servlet.*
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.context.request.RequestContextHolder as RCH
import org.springframework.web.context.WebApplicationContext;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.grails.web.metaclass.*
import org.codehaus.groovy.grails.commons.metaclass.*
import org.codehaus.groovy.grails.web.servlet.*
import org.springframework.validation.Errors
import org.codehaus.groovy.grails.web.pages.GroovyPage
import org.codehaus.groovy.grails.web.metaclass.TagLibMetaClass
import org.codehaus.groovy.grails.commons.TagLibArtefactHandler
import org.codehaus.groovy.grails.commons.ControllerArtefactHandler     
import javax.servlet.http.HttpServletRequest

/**
 * A plug-in that handles the configuration of controllers for Grails
 *
 * @author Graeme Rocher
 * @since 0.4
 */
class ControllersGrailsPlugin {

	def watchedResources = ["file:./grails-app/controllers/**/*Controller.groovy",
							"file:./plugins/*/grails-app/controllers/**/*Controller.groovy",
							"file:./plugins/*/grails-app/taglib/**/*TagLib.groovy",
	                        "file:./grails-app/taglib/**/*TagLib.groovy"]

	def version = GrailsPluginUtils.getGrailsVersion()
	def dependsOn = [core:version,i18n:version]

	def doWithSpring = {
		exceptionHandler(GrailsExceptionResolver) {
			exceptionMappings = ['java.lang.Exception':'/error']
		}
		multipartResolver(CommonsMultipartResolver)
		def urlMappings = [:]
		grailsUrlMappings(UrlMappingFactoryBean) {
			mappings = urlMappings
		}
		simpleGrailsController(SimpleGrailsController.class) {
			grailsApplication = ref("grailsApplication", true)
		}

        if(grails.util.GrailsUtil.isDevelopmentEnv()) {
	    	groovyPageResourceLoader(org.codehaus.groovy.grails.web.pages.DevelopmentGroovyPageResourceLoader) {
					baseResource = "file:./"		
			}
		}

		groovyPagesTemplateEngine(org.codehaus.groovy.grails.web.pages.GroovyPagesTemplateEngine) {
			classLoader = ref("classLoader")
			if(grails.util.GrailsUtil.isDevelopmentEnv()) {
				resourceLoader = groovyPageResourceLoader
			}
		}   
		
		jspViewResolver(GrailsViewResolver) {
			viewClass = org.springframework.web.servlet.view.JstlView.class
			prefix = GrailsApplicationAttributes.PATH_TO_VIEWS
		    suffix = ".jsp"
		    templateEngine = groovyPagesTemplateEngine
			if(grails.util.GrailsUtil.isDevelopmentEnv()) {
				resourceLoader = groovyPageResourceLoader
			}		
		}                        

		
		def handlerInterceptors = [ref("localeChangeInterceptor")]
		grailsUrlHandlerMapping(GrailsUrlHandlerMapping) {
			interceptors = handlerInterceptors
			mappings =  grailsUrlMappings
		}
		handlerMappingTargetSource(HotSwappableTargetSource, grailsUrlHandlerMapping)
		handlerMapping(ProxyFactoryBean) {
			targetSource = handlerMappingTargetSource
			proxyInterfaces = [org.springframework.web.servlet.HandlerMapping]
		}
		
		application.controllerClasses.each { controller ->
			log.debug "Configuring controller $controller.fullName"
			if(controller.available) {
				// configure the controller with AOP proxies for auto-updates and
				// mappings in the urlMappings bean
				configureAOPProxyBean.delegate = delegate
                configureAOPProxyBean(controller, ControllerArtefactHandler.TYPE, org.codehaus.groovy.grails.commons.GrailsControllerClass.class, false)
			}

		}

		// Now go through tag libraries and configure them in spring too. With AOP proxies and so on
		application.tagLibClasses.each { taglib ->
			configureAOPProxyBean.delegate = delegate
			configureAOPProxyBean(taglib, TagLibArtefactHandler.TYPE, org.codehaus.groovy.grails.commons.GrailsTagLibClass.class, true)
		}
	}

	def configureAOPProxyBean = { grailsClass, artefactType, proxyClass, singleton ->
		"${grailsClass.fullName}Class"(MethodInvokingFactoryBean) {
			targetObject = ref("grailsApplication", true)
			targetMethod = "getArtefact"
			arguments = [artefactType, grailsClass.fullName]
		}
		"${grailsClass.fullName}TargetSource"(HotSwappableTargetSource, ref("${grailsClass.fullName}Class"))

		"${grailsClass.fullName}Proxy"(ProxyFactoryBean) {
			targetSource = ref("${grailsClass.fullName}TargetSource")
			proxyInterfaces = [proxyClass]
		}
		"${grailsClass.fullName}"("${grailsClass.fullName}Proxy":"newInstance") { bean ->
			bean.singleton = singleton
			bean.autowire = "byName"
		}

	}

	def doWithWebDescriptor = { webXml ->

		def basedir = System.getProperty("base.dir")
		def grailsEnv = System.getProperty("grails.env")

		def mappingElement = webXml.'servlet-mapping'
		mappingElement + {
			'servlet-mapping' {
				'servlet-name'("grails")
				'url-pattern'("*.dispatch")
			}
		}

		def filters = webXml.filter
		def filterMappings = webXml.'filter-mapping'

		def lastFilter = filters[filters.size()-1]
		def lastFilterMapping = filterMappings[filterMappings.size()-1]
		def charEncodingFilter = filterMappings.find { it.'filter-name'.text() == 'charEncodingFilter'}

		// add the Grails web request filter
		lastFilter + {
			filter {
				'filter-name'('grailsWebRequest')
				'filter-class'(org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequestFilter.getName())
			}     
			filter {
				'filter-name'('urlMapping')
				'filter-class'(org.codehaus.groovy.grails.web.mapping.filter.UrlMappingsFilter.getName())
			}               			
			if(grailsEnv == "development") {
				filter {
					'filter-name'('reloadFilter')
					'filter-class'(org.codehaus.groovy.grails.web.servlet.filter.GrailsReloadServletFilter.getName())
				}
			}
		}
		def grailsWebRequestFilter = {
			'filter-mapping' {
				'filter-name'('grailsWebRequest')
				'url-pattern'("/*")
			}   
			if(grailsEnv == "development") {
                'filter-mapping' {
                    'filter-name'('reloadFilter')
                    'url-pattern'("/*")
                }				
			}
		}
		if(charEncodingFilter) {
			charEncodingFilter + grailsWebRequestFilter
		}
		else {
			lastFilterMapping + grailsWebRequestFilter
		}    
		filterMappings = webXml.'filter-mapping'   
		lastFilterMapping = filterMappings[filterMappings.size()-1]
		
	    lastFilterMapping + {   
			'filter-mapping' {
				'filter-name'('urlMapping')
				'url-pattern'("/*")						
			}
		} 
		// if we're in development environment first add a the reload filter
		// to the web.xml by finding the last filter and appending it after
		if(grailsEnv == "development") {
			// now find the GSP servlet and allow viewing generated source in
			// development mode
			def gspServlet = webXml.servlet.find { it.'servlet-name'?.text() == 'gsp' }
			gspServlet.'servlet-class' + {
				'init-param' {
					description """
		              Allows developers to view the intermediade source code, when they pass
		                a spillGroovy argument in the URL.
							"""
					'param-name'('showSource')
					'param-value'(1)
				}
			}
		}

	}

	/**
	 * This creates the difference dynamic methods and properties on the controllers. Most methods
	 * are implemented by looking up the current request from the RequestContextHolder (RCH)
	 */

	def registerCommonObjects(metaClass, application) {
	   	def paramsObject = {->
			RCH.currentRequestAttributes().params
		}
	    def flashObject = {->
				RCH.currentRequestAttributes().flashScope
		}
	   	def sessionObject = {->
			RCH.currentRequestAttributes().session
		}
	   	def requestObject = {->
			RCH.currentRequestAttributes().currentRequest
		}
	   	def responseObject = {->
			RCH.currentRequestAttributes().currentResponse
		}
	   	def servletContextObject = {->
				RCH.currentRequestAttributes().servletContext
		}
	   	def grailsAttrsObject = {->
				RCH.currentRequestAttributes().attributes
		}

		   // the params object
		   metaClass.getParams = paramsObject
		   // the flash object
		   metaClass.getFlash = flashObject
		   // the session object
			metaClass.getSession = sessionObject
		   // the request object
			metaClass.getRequest = requestObject
		   // the servlet context
		   metaClass.getServletContext = servletContextObject
		   // the response object
			metaClass.getResponse = responseObject
		   // The GrailsApplicationAttributes object
		   metaClass.getGrailsAttributes = grailsAttrsObject
		   // The GrailsApplication object
		   metaClass.getGrailsApplication = {-> RCH.currentRequestAttributes().attributes.grailsApplication }

		   metaClass.getPluginContextPath = {-> 
				def resource = application.getResourceForClass(delegate.class) 
				GRU.getStaticResourcePathForResource(resource, request.contextPath)
		   }
	}


	def doWithDynamicMethods = { ctx ->

		// add common objects and out variable for tag libraries
		def registry = GroovySystem.getMetaClassRegistry()
	   	application.tagLibClasses.each { taglib ->
	   		def metaClass = taglib.metaClass
	   		registerCommonObjects(metaClass, application)

	   		metaClass.throwTagError = { String message ->
	   			throw new org.codehaus.groovy.grails.web.taglib.exceptions.GrailsTagException(message)
	   		}
	   		metaClass.getOut = {->
	   			RCH.currentRequestAttributes().out
	   		}
	   		metaClass.setOut = { Writer newOut ->
	   			RCH.currentRequestAttributes().out = newOut
	   		}

	   		def adaptedMetaClass = new TagLibMetaClass(taglib.metaClass)

	   		registry.setMetaClass(taglib.clazz, adaptedMetaClass)
	   		ctx.getBean(taglib.fullName).metaClass = adaptedMetaClass
	   	}
		// add commons objects and dynamic methods like render and redirect to controllers
		application.controllerClasses.each { controller ->
		   def metaClass = controller.metaClass
			registerCommonObjects(metaClass, application)

			metaClass.getActionUri = {-> "/$controllerName/$actionName".toString()	}
			metaClass.getControllerUri = {-> "/$controllerName".toString()	}
		    metaClass.getTemplateUri = { String name ->
		    	def webRequest = RCH.currentRequestAttributes()
		    	webRequest.attributes.getTemplateUri(name, webRequest.currentRequest)
		    }
		    metaClass.getViewUri = { String name ->
		    	def webRequest = RCH.currentRequestAttributes()
		    	webRequest.attributes.getViewUri(name, webRequest.currentRequest)
		    }
			metaClass.getActionName = {->
				RCH.currentRequestAttributes().actionName
			}
			metaClass.getControllerName = {->
				RCH.currentRequestAttributes().controllerName
			}

			metaClass.setErrors = { Errors errors ->
				RCH.currentRequestAttributes().setAttribute( GrailsApplicationAttributes.ERRORS, errors, 0)
			}
		    metaClass.getErrors = {->
		   		RCH.currentRequestAttributes().getAttribute(GrailsApplicationAttributes.ERRORS, 0)
		    }
			metaClass.setModelAndView = { ModelAndView mav ->
				RCH.currentRequestAttributes().setAttribute( GrailsApplicationAttributes.MODEL_AND_VIEW, mav, 0)
			}
		    metaClass.getModelAndView = {->
	   			RCH.currentRequestAttributes().getAttribute(GrailsApplicationAttributes.MODEL_AND_VIEW, 0)
		    }
		    metaClass.getChainModel = {->
		    	RCH.currentRequestAttributes().flashScope["chainModel"]
		    }
			metaClass.hasErrors = {->
				errors?.hasErrors() ? true : false
			}

			def redirect = new RedirectDynamicMethod(ctx)
			def chain = new ChainDynamicMethod()
			def render = new RenderDynamicMethod()
			def bind = new BindDynamicMethod()
			// the redirect dynamic method
			metaClass.redirect = { Map args ->
				redirect.invoke(delegate,"redirect",args)
			}
		    metaClass.chain = { Map args ->
		    	chain.invoke(delegate, "chain",args)
		    }
		    // the render method
		    metaClass.render = { String txt ->
				render.invoke(delegate, "render",[txt] as Object[])
		    }
		    metaClass.render = { Map args ->
				render.invoke(delegate, "render",[args] as Object[])
	    	}
		    metaClass.render = { Closure c ->
				render.invoke(delegate,"render", [c] as Object[])
	    	}
		    metaClass.render = { Map args, Closure c ->
				render.invoke(delegate,"render", [args, c] as Object[])
		    }
		    // the bindData method
		    metaClass.bindData = { Object target, Object args ->
		    	bind.invoke(delegate, "bindData", [target, args] as Object[])
		    }
		    metaClass.bindData = { Object target, Object args, List disallowed ->
		    	bind.invoke(delegate, "bindData", [target, args, disallowed] as Object[])
		    }
		    metaClass.bindData = { Object target, Object args, List disallowed, String filter ->
		    	bind.invoke(delegate, "bindData", [target, args, disallowed, filter] as Object[])
		    }
		    metaClass.bindData = { Object target, Object args, String filter ->
		    	bind.invoke(delegate, "bindData", [target, args, filter] as Object[])
		    }
			
			// look for actions that accept command objects and configure
			// each of the command object types
			def commandObjectClasses = controller.commandObjectClasses
			commandObjectClasses.each { commandObjectClass ->
	            def commandObject = commandObjectClass.newInstance()
           		def commandObjectMetaClass = commandObject.metaClass
           		commandObjectMetaClass.setErrors = { Errors errors ->
					RCH.currentRequestAttributes().setAttribute( "${commandObjectClass.name}_errors", errors, 0)
				}
	            commandObjectMetaClass.getErrors = {->
			   		RCH.currentRequestAttributes().getAttribute( "${commandObjectClass.name}_errors", 0)
		   		}

           		commandObjectMetaClass.hasErrors = {->
					errors?.hasErrors() ? true : false
		    	}
           		def validationClosure = GCU.getStaticPropertyValue(commandObjectClass, 'constraints')
           		if(validationClosure) {
           			def constrainedPropertyBuilder = new ConstrainedPropertyBuilder(commandObject)
           			validationClosure.setDelegate(constrainedPropertyBuilder)
    	       		validationClosure()
	           		commandObjectMetaClass.constraints = constrainedPropertyBuilder.constrainedProperties
           		} else {
           		    commandObjectMetaClass.constraints = [:]
        	   	}
			}
		}
	}

	def doWithApplicationContext = { ctx ->
        application.domainClasses.each { domainClass ->
            def metaClass = GMCU.registry.getMetaClass(domainClass.getClazz())
			log.debug("meta class of ${domainClass.clazz} is ${metaClass.getClass()}")
            if(metaClass instanceof DynamicMethodsMetaClass) {
                   metaClass.dynamicMethods.addDynamicConstructor(new DataBindingDynamicConstructor(ctx))
                   metaClass.dynamicMethods.addDynamicProperty(new SetPropertiesDynamicProperty())
            }
        }
	}

	def onChange = { event -> 
        if(application.isArtefactOfType(ControllerArtefactHandler.TYPE, event.source)) {
			def context = event.ctx
			if(!context) {
				if(log.isDebugEnabled())
					log.debug("Application context not found. Can't reload")
				return
			}
			boolean isNew = application.getControllerClass(event.source?.name) ? false : true
			def controllerClass = application.addArtefact(ControllerArtefactHandler.TYPE, event.source)
			
			

			if(isNew) {
				log.info "Controller ${event.source} added. Configuring.."
				// we can create the bean definitions from the oroginal configureAOPProxyBean closure
				// by currying it, which populates the values within the curried closure
				// once that is done we pass it to the "beans" method which will return a BeanBuilder
				def beanConfigs = 	  configureAOPProxyBean.curry(controllerClass, 
																  ControllerArtefactHandler.TYPE, 				
																  org.codehaus.groovy.grails.commons.GrailsControllerClass.class,
																  false)
               	def beanDefinitions = beans(beanConfigs) 
				// now that we have a BeanBuilder calling registerBeans and passing the app ctx will
				// register the necessary beans with the given app ctx
                beanDefinitions.registerBeans(event.ctx)   														
				
			}
			else {
				if(log.isDebugEnabled())
					log.debug("Controller ${event.source} changed. Reloading...")
				
				def controllerTargetSource = context.getBean("${controllerClass.fullName}TargetSource")
				controllerTargetSource.swap(controllerClass)				
			}

		}
		else if(application.isArtefactOfType(TagLibArtefactHandler.TYPE, event.source)) {
			boolean isNew = application.getTagLibClass(event.source?.name) ? false : true
			def taglibClass = application.addArtefact(TagLibArtefactHandler.TYPE, event.source)
			if(taglibClass) {
				// replace tag library bean
				def beanName = taglibClass.fullName
				def beans = beans {
					"$beanName"(taglibClass.getClazz()) { bean ->
						bean.autowire =  true
					}					
				}
				if(event.ctx) {
					event.ctx.registerBeanDefinition(beanName, beans.getBeanDefinition(beanName))
				}
			}
		}  
		
		event.manager?.getGrailsPlugin("controllers")?.doWithDynamicMethods(event.ctx)
	}
}