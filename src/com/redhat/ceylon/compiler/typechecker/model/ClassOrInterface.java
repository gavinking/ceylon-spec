package com.redhat.ceylon.compiler.typechecker.model;

import java.util.ArrayList;
import java.util.List;


public abstract class ClassOrInterface extends TypeDeclaration {

    private List<Declaration> members = new ArrayList<Declaration>(3);
    private List<Annotation> annotations = new ArrayList<Annotation>(4);
    
    @Override
    public List<Annotation> getAnnotations() {
        return annotations;
    }
    
    @Override
    public List<Declaration> getMembers() {
        return members;
    }
    
    @Override
    public void addMember(Declaration declaration) {
        members.add(declaration);
    }
    
    @Override
    public boolean isMember() {
        return getContainer() instanceof ClassOrInterface;
    }

    @Override
    public ProducedType getDeclaringType(Declaration d) {
        //look for it as a declared or inherited 
        //member of the current class or interface
    	if (d.isMember()) {
	        ProducedType st = getType().getSupertype((TypeDeclaration) d.getContainer());
	        //return st;
	        if (st!=null) {
	            return st;
	        }
	        else {
	            return getContainer().getDeclaringType(d);
	        }
    	}
    	else {
    		return null;
    	}
    }

    public abstract boolean isAbstract();

    @Override
    public DeclarationKind getDeclarationKind() {
        return DeclarationKind.TYPE;
    }
    
    @Override
    protected int hashCodeForCache() {
        int ret = 17;
        ret = Util.addHashForModule(ret, this);
        if(isToplevel())
            ret = (37 * ret) + getQualifiedNameString().hashCode();
        else{
            ret = (37 * ret) + getContainer().hashCode();
            if (getName()!=null) {
                ret = (37 * ret) + getName().hashCode();
            }
        }
        return ret;
    }
    
    @Override
    protected boolean equalsForCache(Object o) {
        if(o == null || o instanceof ClassOrInterface == false)
            return false;
        ClassOrInterface b = (ClassOrInterface) o;
        if(!Util.sameModule(this, b))
            return false;
        if(isToplevel()){
            if(!b.isToplevel())
                return false;
            return getQualifiedNameString().equals(b.getQualifiedNameString());
        }else{
            if(b.isToplevel())
                return false;
            return getContainer().equals(b.getContainer())
                    && getName()==b.getName()
                    || (getName()!=null && b.getName()!=null  
                            && getName().equals(b.getName()));
        }
    }
    
    @Override
    public void clearProducedTypeCache() {
        Util.clearProducedTypeCache(this);
    }
}
