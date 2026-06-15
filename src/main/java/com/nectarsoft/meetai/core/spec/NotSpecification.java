package com.nectarsoft.meetai.core.spec;

class NotSpecification<T> implements Specification<T> {

    private final Specification<T> inner;

    NotSpecification(Specification<T> inner) {
        this.inner = inner;
    }

    @Override
    public boolean isSatisfiedBy(T candidate) {
        return !inner.isSatisfiedBy(candidate);
    }
}
