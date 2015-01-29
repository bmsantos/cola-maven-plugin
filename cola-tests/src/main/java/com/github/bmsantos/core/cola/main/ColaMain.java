package com.github.bmsantos.core.cola.main;

import static com.github.bmsantos.core.cola.config.ConfigurationManager.config;
import static com.github.bmsantos.core.cola.formatter.FeaturesLoader.loadFeaturesFrom;
import static com.github.bmsantos.core.cola.utils.ColaUtils.binaryFileExists;
import static com.github.bmsantos.core.cola.utils.ColaUtils.binaryToOsClass;
import static com.github.bmsantos.core.cola.utils.ColaUtils.classToBinary;
import static com.github.bmsantos.core.cola.utils.ColaUtils.isSet;
import static java.lang.String.format;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.ASM4;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.bmsantos.core.cola.exceptions.ColaExecutionException;
import com.github.bmsantos.core.cola.formatter.FeatureDetails;
import com.github.bmsantos.core.cola.injector.InjectorClassVisitor;
import com.github.bmsantos.core.cola.injector.MethodRemoverClassVisitor;
import com.github.bmsantos.core.cola.provider.IColaProvider;

public class ColaMain {

    private static Logger log = LoggerFactory.getLogger(ColaMain.class);

    private IColaProvider provider;
    private String ideBaseClass;
    private String ideBaseClassTest;

    private List<String> failures;

    public ColaMain(final String ideBaseClass, final String ideBaseClassTest) {
        this.ideBaseClass = ideBaseClass;
        this.ideBaseClassTest = ideBaseClassTest;
    }

    public List<String> getFailures() {
        return failures;
    }

    public void execute(final IColaProvider provider) throws ColaExecutionException {
        this.provider = provider;

        failures = new ArrayList<>();

        if (!isSet(provider)) {
            return;
        }

        final List<String> targetClasses = provider.getTargetClasses();
        if (!isSet(targetClasses)) {
            return;
        }

        if (isValidIdeBaseClass()) {
            try {
                ideBaseClass = processIdeBaseClass();
                targetClasses.remove(ideBaseClass);
            } catch (final Throwable t) {
                log.info(config.error("failed.ide"), t);
            }
        }

        for (final String className : targetClasses) {
            try {
                final Class<?> annotatedClass = provider.getTargetClassLoader().loadClass(classToBinary(className));

                final List<FeatureDetails> featureList = loadFeaturesFrom(annotatedClass);

                final ClassWriter classWritter = new ClassWriter(COMPUTE_MAXS);

                final InjectorClassVisitor injectorClassVisitor = new InjectorClassVisitor(ASM4, classWritter,
                    featureList);

                processClass(className, classWritter, injectorClassVisitor);
            } catch (final Throwable t) {
                log.error(format(config.error("failed.process.file"), className), t);
                failures.add(format(config.error("failed.processing"), className, t.getMessage()));
            }
        }

        if (!failures.isEmpty()) {
            log.error(format(config.error("failed.tests"), failures.size(), targetClasses.size()));
            for (final String failure : failures) {
                log.error(failure);
            }

            throw new ColaExecutionException(config.error("processing"));
        }
    }

    private String processIdeBaseClass() throws Exception {

        ideBaseClass = binaryToOsClass(ideBaseClass);

        final ClassWriter cw = new ClassWriter(COMPUTE_MAXS);
        final MethodRemoverClassVisitor remover = new MethodRemoverClassVisitor(ASM4, cw, ideBaseClassTest);

        processClass(ideBaseClass, cw, remover);

        return ideBaseClass;
    }

    private boolean isValidIdeBaseClass() {
        if (!isSet(ideBaseClassTest)) {
            log.warn(config.warn("missing.ide.test"));
            ideBaseClassTest = config.getProperty("default.ide.test");
        }

        if (binaryFileExists(provider.getTargetDirectory(), ideBaseClass)) {
            return true;
        } else {
            // Try default
            log.info(config.info("missing.ide.class"));

            ideBaseClass = config.getProperty("default.ide.class");
            if (binaryFileExists(provider.getTargetDirectory(), ideBaseClass)) {
                log.info(config.info("found.default.ide.class"));
                return true;
            } else {
                log.info(config.info("missing.default.ide.class"));
            }
        }

        return false;
    }

    private void processClass(final String className, final ClassWriter cw, final ClassVisitor classVisitor)
        throws Exception {

        final String filePath = provider.getTargetDirectory() + className;
        log.info(config.info("processing") + filePath);

        final InputStream in = provider.getTargetClassLoader().getResourceAsStream(className);
        final ClassReader classReader = new ClassReader(in);
        classReader.accept(classVisitor, 0);

        final File file = new File(filePath);
        final DataOutputStream dout = new DataOutputStream(new FileOutputStream(file));

        dout.write(cw.toByteArray());
        dout.close();
    }
}
