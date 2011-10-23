/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.jpa.openjpa;

import static org.jboss.as.jpa.JpaLogger.JPA_LOGGER;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import org.apache.openjpa.conf.OpenJPAConfiguration;
import org.apache.openjpa.conf.OpenJPAConfigurationImpl;
import org.apache.openjpa.enhance.PCClassFileTransformer;
import org.apache.openjpa.lib.conf.ConfigurationProvider;
import org.apache.openjpa.lib.conf.ProductDerivations;
import org.apache.openjpa.lib.util.Options;
import org.apache.openjpa.lib.util.TemporaryClassLoader;
import org.apache.openjpa.util.ClassResolver;
import org.jboss.as.jpa.spi.JtaManager;
import org.jboss.as.jpa.spi.ManagementAdaptor;
import org.jboss.as.jpa.spi.PersistenceProviderAdaptor;
import org.jboss.as.jpa.spi.PersistenceUnitMetadata;
import org.jboss.msc.service.ServiceName;

/**
 * Implements the PersistenceProviderAdaptor for OpenJPA 2.x.
 *
 * @author Antti Laisi
 */
public class OpenJPAPersistenceProviderAdaptor implements PersistenceProviderAdaptor {

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addProviderProperties(Map properties, PersistenceUnitMetadata pu) {
        properties.put("openjpa.TransactionMode", "managed");
        properties.put("openjpa.ManagedRuntime", "jndi(TransactionManagerName=java:jboss/TransactionManager)");
    }

    @Override
    public void beforeCreateContainerEntityManagerFactory(PersistenceUnitMetadata pu) {
        addClassTransformer(pu);
    }

    @Override
    public void injectJtaManager(JtaManager jtaManager) {

    }

    @Override
    public Iterable<ServiceName> getProviderDependencies(PersistenceUnitMetadata pu) {
        return null;
    }

    @Override
    public void afterCreateContainerEntityManagerFactory(PersistenceUnitMetadata pu) {

    }

    @Override
    public ManagementAdaptor getManagementAdaptor() {
        return null;
    }

    private void addClassTransformer(PersistenceUnitMetadata pu) {
        try {
            ClassLoader moduleClassLoader = pu.getClassLoader();

            // Get org.jboss.modules.ModuleClassLoader::transformer
            Field transformer = moduleClassLoader.getClass().getDeclaredField("transformer");
            transformer.setAccessible(true);
            Object delegatingClassFileTransformer = transformer.get(moduleClassLoader);

            // Call org.jboss.as.server.deployment.module.DelegatingClassFileTransformer::addTransformer
            Method addTransformer = delegatingClassFileTransformer.getClass().getMethod("addTransformer",
                    ClassFileTransformer.class);
            addTransformer.invoke(delegatingClassFileTransformer, createPCTransformer(pu));

        } catch (Exception e) {
            JPA_LOGGER.warn("Dynamically adding OpenJPA class transformer failed", e);
        }
    }

    private ClassFileTransformer createPCTransformer(PersistenceUnitMetadata pu) {

        Options options = new Options();
        OpenJPAConfiguration conf = new OpenJPAConfigurationImpl();

        ConfigurationProvider provider = ProductDerivations.load("META-INF/persistence.xml", pu.getPersistenceUnitName(),
                pu.getClassLoader());
        provider.setInto(conf);
        options.setInto(conf);

        final ClassLoader tmpLoader = new TemporaryClassLoader(pu.getClassLoader());
        conf.setClassResolver(new ClassResolver() {
            @Override
            public ClassLoader getClassLoader(Class<?> context, ClassLoader env) {
                return tmpLoader;
            }
        });

        conf.setInitializeEagerly(false);
        conf.instantiateAll();

        return new PCClassFileTransformer(conf.newMetaDataRepositoryInstance(), options, tmpLoader);
    }

}
