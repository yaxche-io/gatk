package org.broadinstitute.hellbender.utils.help;

import org.broadinstitute.barclay.help.DocWorkUnit;
import org.broadinstitute.barclay.help.DocWorkUnitHandler;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.utils.runtime.RuntimeUtils;

/**
 * Custom DocWorkUnit used for generating GATK WDL. Overrides the defaults to provide tool
 * names that are annotated with a " (Picard)" suffix for Picard tools.
 *
 * NOTE: Methods in this class are intended to be called by Gradle/Javadoc only, and should not be called
 * by methods that are used by the GATK runtime. This class has a dependency on com.sun.javadoc classes,
 * which may not be present since they're not provided as part of the normal GATK runtime classpath.
 */
//TODO: this was only to override Picard tools to modify their name (with "(Picard"), which is unnecessary and
//undesireable for WDL gen, so if thats all it winds up doing, remove the class and just use the base class
@SuppressWarnings("removal")
public class GATKWDLWorkUnit extends DocWorkUnit {

    public GATKWDLWorkUnit(
            final DocWorkUnitHandler workUnitHandler,
            final DocumentedFeature documentedFeatureAnnotation,
            final com.sun.javadoc.ClassDoc classDoc,
            final Class<?> clazz) {
        super(workUnitHandler, documentedFeatureAnnotation, classDoc, clazz);
    }

    @Override
    public String getName() {
        //TODO: this is a no-op, if no other functionality gets added to this class, then delete this class
        return super.getName();
    }

    /**
     * Sort in order of the name of this WorkUnit
     */
    @Override
    public int compareTo(DocWorkUnit other) {
        return this.getName().compareTo(other.getName());
    }
}
