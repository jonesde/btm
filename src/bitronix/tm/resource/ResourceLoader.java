package bitronix.tm.resource;

import bitronix.tm.TransactionManagerServices;
import bitronix.tm.internal.InitializationException;
import bitronix.tm.internal.PropertyUtils;
import bitronix.tm.internal.Service;
import bitronix.tm.resource.common.XAResourceProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.XAConnectionFactory;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.XADataSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

/**
 * XA resources pools configurator & loader.
 * <p>{@link ResourceLoader} relies on the optional <code>bitronix.tm.resource.configuration</code> propery to load the
 * JDBC datasources ({@link bitronix.tm.resource.jdbc.PoolingDataSource}) and JMS connection factories configuration
 * ({@link bitronix.tm.resource.jms.PoolingConnectionFactory}) file and create the resources.</p>
 * <p>There is an optional <code>bitronix.tm.resource.bind</code> property that makes ResourceLoader bind resources
 * to JNDI.</p>
 * <p>When <code>bitronix.tm.resource.configuration</code> is not specified, ResourceLoader is disabled and resources
 * should be manually created.</p>
 * <p>&copy; Bitronix 2005, 2006, 2007</p>
 *
 * @author lorban
 */
public class ResourceLoader implements Service {

    private final static Logger log = LoggerFactory.getLogger(ResourceLoader.class);
    private final static String JDBC_RESOUCE_CLASSNAME = "bitronix.tm.resource.jdbc.PoolingDataSource";
    private final static String JMS_RESOUCE_CLASSNAME = "bitronix.tm.resource.jms.PoolingConnectionFactory";

    private boolean bindJndi;
    private Map resourcesByUniqueName;

    /**
     * Create a ResourceLoader and load the resources configuration file specified in
     * <code>bitronix.tm.resource.configuration</code> property.
     */
    public ResourceLoader() {
        String filename = TransactionManagerServices.getConfiguration().getResourceConfigurationFilename();
        if (filename != null) {
            if (!new File(filename).exists())
                throw new ResourceConfigurationException("cannot find resources configuration file '" + filename + "', missing or invalid value of property: bitronix.tm.resource.configuration");
            log.info("reading resources configuration from " + filename);
            init(filename);
        }
        else {
            if (log.isDebugEnabled()) log.debug("no resource configuration file specified");
            resourcesByUniqueName = Collections.EMPTY_MAP;
        }
    }

    /**
     * Get a Map with the configured uniqueName as key and {@link XAResourceProducer} as value.
     * @return a Map using the uniqueName as key and {@link XAResourceProducer} as value.
     */
    public Map getResources() {
        return resourcesByUniqueName;
    }

    /**
     * Bind all configured resources to JNDI if the <code>bitronix.tm.resource.bind</code> property has been set to true.
     * Resources are bound under a JNDI name equals to their unique name.
     * @throws NamingException if an error happens during binding.
     */
    public void bindAll() throws NamingException {
        if (!bindJndi) {
            if (log.isDebugEnabled()) log.debug("JNDI resource binding disabled");
            return;
        }
        if (resourcesByUniqueName.size() == 0) {
            if (log.isDebugEnabled()) log.debug("no resource configuration file, nothing to bind");
            return;
        }

        Context ctx = new InitialContext();
        if (log.isDebugEnabled()) log.debug("binding resources to JNDI context " + ctx);

        try {
            // Bind resources to JNDI
            Map resources = getResources();
            if (resources != null) {
                Iterator it = resources.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry entry = (Map.Entry) it.next();
                    XAResourceProducer producer = (XAResourceProducer) entry.getValue();
                    bind(ctx, producer.getUniqueName());
                }
                log.info("bound " + resources.size() + " resource(s) to JNDI");
            }
        } finally {
            if (log.isDebugEnabled()) log.debug("closing context");
            ctx.close();
        }
    }

    public synchronized void shutdown() {
        if (log.isDebugEnabled()) log.debug("resource loader has registered " + resourcesByUniqueName.entrySet().size() + " resource(s), unregistering them now");
        Iterator it = resourcesByUniqueName.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            XAResourceProducer producer = (XAResourceProducer) entry.getValue();
            if (log.isDebugEnabled()) log.debug("closing " + producer);
            try {
                producer.close();
            } catch (Exception ex) {
                log.warn("error closing resource " + producer, ex);
            }
        }
        resourcesByUniqueName.clear();
    }

    /*
     * Internal impl.
     */

    /**
     * Create an unitialized {@link XAResourceProducer} implementation which depends on the XA resource class name.
     * @param xaResourceClassName an XA resource class name.
     * @return a {@link XAResourceProducer} implementation.
     * @throws ClassNotFoundException if the {@link XAResourceProducer} cannot be instanciated.
     * @throws IllegalAccessException if the {@link XAResourceProducer} cannot be instanciated.
     * @throws InstantiationException if the {@link XAResourceProducer} cannot be instanciated.
     */
    private static XAResourceProducer instanciate(String xaResourceClassName) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        Class clazz = Thread.currentThread().getContextClassLoader().loadClass(xaResourceClassName);

        // resource classes are instanciated via reflection so that there is no hard class binding between this internal
        // transaction manager service and 3rd party libraries like the JMS ones.
        // This allows using the TM with a 100% JDBC application without requiring JMS libraries.

        if (XADataSource.class.isAssignableFrom(clazz)) {
            return (XAResourceProducer) Thread.currentThread().getContextClassLoader().loadClass(JDBC_RESOUCE_CLASSNAME).newInstance();
        }
        else if (XAConnectionFactory.class.isAssignableFrom(clazz)) {
            return (XAResourceProducer) Thread.currentThread().getContextClassLoader().loadClass(JMS_RESOUCE_CLASSNAME).newInstance();
        }
        else
            return null;
    }

    /**
     * Read the resources properties file and create {@link XAResourceProducer} accordingly.
     * @param propertiesFilename the name of the properties file to load.
     */
    private void init(String propertiesFilename) {
        try {
            FileInputStream fis = null;
            Properties properties;
            try {
                fis = new FileInputStream(propertiesFilename);
                properties = new Properties();
                properties.load(fis);
            } finally {
                if (fis != null) fis.close();
            }

            bindJndi = Boolean.valueOf(properties.getProperty("bitronix.tm.resource.bind")).booleanValue();
            initXAResourceProducers(properties);
        } catch (IOException ex) {
            throw new InitializationException("cannot create resource binder", ex);
        }
    }

    private static void bind(Context ctx, String uniqueName) throws NamingException {
        XAResourceProducer producer = ResourceRegistrar.get(uniqueName);

        String[] names = uniqueName.split("/");
        if (log.isDebugEnabled()) log.debug("binding " + uniqueName + " under " + (names.length -1) + " subcontext(s) to '" + ctx + "'");

        for (int i = 0; i < names.length -1; i++) {
            String name = names[i];
            if (log.isDebugEnabled()) log.debug("subcontext " + i + ": " + name);
            try {
                Object obj = ctx.lookup(name);
                if (!(obj instanceof Context))
                    throw new InitializationException("cannot bind resource " + producer + " as JNDI subcontext name '" + name + "' is already taken by a non-context object");
                ctx = (Context) obj;
                if (log.isDebugEnabled()) log.debug("subcontext " + i + " exists");
            } catch (NamingException ex) {
                if (log.isDebugEnabled()) log.debug("subcontext " + i + " does not exist, creating it");
                ctx = ctx.createSubcontext(name);
            }
        }

        ctx.bind(names[names.length -1], producer);
    }

    /**
     * Initialize {@link XAResourceProducer}s given a set of properties.
     * @param properties the properties to use for initialization.
     */
    void initXAResourceProducers(Properties properties) {
        Map entries = buildConfigurationEntriesMap(properties);

        resourcesByUniqueName = new HashMap();
        for (Iterator it = entries.entrySet().iterator(); it.hasNext();) {
            Map.Entry entry = (Map.Entry) it.next();
            String configuredName = (String) entry.getKey();
            List propertyPairs = (List) entry.getValue();
            XAResourceProducer producer = buildXAResourceProducer(configuredName, propertyPairs);

            if (log.isDebugEnabled()) log.debug("creating resource " + producer);
            producer.init();

            resourcesByUniqueName.put(producer.getUniqueName(), producer);
        }
    }

    /**
     * Create a map using the configured resource name as the key and a List of PropertyPair objects as the value.
     * @param properties object to analyze.
     * @return the built map.
     */
    private Map buildConfigurationEntriesMap(Properties properties) {
        Map entries = new HashMap();
        Iterator it = properties.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();

            if (key.startsWith("resource.")) {
                String[] keyParts = key.split("\\.");
                if (keyParts.length < 3 || keyParts.length > 4) {
                    log.warn("ignoring invalid entry in configuration file: " + key);
                    continue;
                }
                String configuredName = keyParts[1];
                String propertyName = keyParts[2];
                if (keyParts.length == 4) {
                    propertyName += "." + keyParts[3];
                }

                List pairs = (List) entries.get(configuredName);
                if (pairs == null) {
                    pairs = new ArrayList();
                    entries.put(configuredName, pairs);
                }

                pairs.add(new PropertyPair(propertyName, value));
            }
        }
        return entries;
    }

    /**
     * Build a populated {@link XAResourceProducer} out of a list of property pairs and the config name.
     * @param configuredName index name of the config file.
     * @param propertyPairs the properties attached to this index.
     * @return a populated {@link XAResourceProducer}.
     * @throws ResourceConfigurationException if the {@link XAResourceProducer} cannot be built.
     */
    private XAResourceProducer buildXAResourceProducer(String configuredName, List propertyPairs) throws ResourceConfigurationException {
        String lastPropertyName = "className";
        try {
            XAResourceProducer producer = createBean(configuredName, propertyPairs);

            for (int i = 0; i < propertyPairs.size(); i++) {
                PropertyPair propertyPair = (PropertyPair) propertyPairs.get(i);
                lastPropertyName = propertyPair.getName();
                PropertyUtils.setProperty(producer, propertyPair.getName(), propertyPair.getValue());
            }
            if (producer.getUniqueName() == null)
                throw new ResourceConfigurationException("missing mandatory property <uniqueName> for resource <" + configuredName + "> in resources configuration file");

            return producer;
        } catch (ResourceConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResourceConfigurationException("cannot configure resource for configuration entries with name <" + configuredName + ">" + " - failing property is <" + lastPropertyName + ">", ex);
        }
    }

    /**
     * Create an unpopulated, uninitialized {@link XAResourceProducer} instance depending on the className value.
     * @param configuredName the properties configured name.
     * @param propertyPairs a list of {@link PropertyPair}s.
     * @return a {@link XAResourceProducer}.
     * @throws ClassNotFoundException if the {@link XAResourceProducer} cannot be instanciated.
     * @throws IllegalAccessException if the {@link XAResourceProducer} cannot be instanciated.
     * @throws InstantiationException if the {@link XAResourceProducer} cannot be instanciated.
     */
    private XAResourceProducer createBean(String configuredName, List propertyPairs) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        for (int i = 0; i < propertyPairs.size(); i++) {
            PropertyPair propertyPair = (PropertyPair) propertyPairs.get(i);
            if (propertyPair.getName().equals("className")) {
                String className = propertyPair.getValue();
                XAResourceProducer producer = instanciate(className);
                if (producer == null)
                    throw new ResourceConfigurationException("property <className> " +
                            "for resource <" + configuredName + "> in resources configuration file " +
                            "must be the name of a class implementing either javax.sql.XADataSource or javax.jms.XAConnectionFactory");
                return producer;
            }
        }
        throw new ResourceConfigurationException("missing mandatory property <className> for resource <" + configuredName + "> in resources configuration file");
    }


    private class PropertyPair {
        private String name;
        private String value;

        public PropertyPair(String key, String value) {
            this.name = key;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String toString() {
            return name + "/" + value;
        }
    }

}