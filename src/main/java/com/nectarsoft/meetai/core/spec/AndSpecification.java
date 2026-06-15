package com.nectarsoft.meetai.core.spec;

class AndSpecification<T> implements Specification<T> {

    private final Specification<T> left;
    private final Specification<T> right;

    AndSpecification(Specification<T> left, Specification<T> right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean isSatisfiedBy(T candidate) {
        return left.isSatisfiedBy(candidate) && right.isSatisfiedBy(candidate);
    }

    @Override
    public String describeFailure(T candidate) {
        if (!left.isSatisfiedBy(candidate)) return left.describeFailure(candidate);
        if (!right.isSatisfiedBy(candidate)) return right.describeFailure(candidate);
        return "조건 만족";
    }
}
