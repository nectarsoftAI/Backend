package com.nectarsoft.meetai.core.spec;

/** Specification 패턴 — 청크/오디오 유효성 검증 조건 조합 */
public interface Specification<T> {

    boolean isSatisfiedBy(T candidate);

    default String describeFailure(T candidate) {
        return "조건 불만족";
    }

    default Specification<T> and(Specification<T> other) {
        return new AndSpecification<>(this, other);
    }

    default Specification<T> or(Specification<T> other) {
        return new OrSpecification<>(this, other);
    }

    default Specification<T> not() {
        return new NotSpecification<>(this);
    }
}
