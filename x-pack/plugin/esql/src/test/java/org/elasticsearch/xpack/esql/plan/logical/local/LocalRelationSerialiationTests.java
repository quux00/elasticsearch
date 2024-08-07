/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.local;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockUtils;
import org.elasticsearch.xpack.esql.TestBlockFactory;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.expression.function.AbstractFunctionTestCase;
import org.elasticsearch.xpack.esql.plan.logical.AbstractLogicalPlanSerializationTests;

import java.io.IOException;
import java.util.List;

public class LocalRelationSerialiationTests extends AbstractLogicalPlanSerializationTests<LocalRelation> {
    public static LocalRelation randomLocalRelation() {
        Source source = randomSource();
        List<Attribute> output = randomFieldAttributes(1, 10, true);
        LocalSupplier supplier = randomLocalSupplier(output);
        return new LocalRelation(source, output, supplier);
    }

    private static LocalSupplier randomLocalSupplier(List<Attribute> attributes) {
        Block[] blocks = new Block[attributes.size()];
        for (int b = 0; b < blocks.length; b++) {
            blocks[b] = BlockUtils.constantBlock(
                TestBlockFactory.getNonBreakingInstance(),
                AbstractFunctionTestCase.randomLiteral(attributes.get(b).dataType()).value(),
                1
            );
        }
        return LocalSupplier.of(blocks);
    }

    @Override
    protected LocalRelation createTestInstance() {
        return randomLocalRelation();
    }

    @Override
    protected LocalRelation mutateInstance(LocalRelation instance) throws IOException {
        if (randomBoolean()) {
            List<Attribute> output = instance.output();
            LocalSupplier supplier = randomValueOtherThan(instance.supplier(), () -> randomLocalSupplier(output));
            return new LocalRelation(instance.source(), output, supplier);
        } else {
            List<Attribute> output = randomValueOtherThan(instance.output(), () -> randomFieldAttributes(1, 10, true));
            LocalSupplier supplier = randomLocalSupplier(output);
            return new LocalRelation(instance.source(), output, supplier);
        }
    }
}
