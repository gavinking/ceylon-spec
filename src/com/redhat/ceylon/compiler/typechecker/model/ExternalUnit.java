package com.redhat.ceylon.compiler.typechecker.model;

import java.util.Map;
import java.util.WeakHashMap;

import com.redhat.ceylon.compiler.typechecker.context.PhasedUnit;

public class ExternalUnit extends Unit {
    private final Map<PhasedUnit,Boolean> dependentsOf = new WeakHashMap<PhasedUnit,Boolean>();

    public Map<PhasedUnit,Boolean> getDependentsOf() {
        return dependentsOf;
    }
}
