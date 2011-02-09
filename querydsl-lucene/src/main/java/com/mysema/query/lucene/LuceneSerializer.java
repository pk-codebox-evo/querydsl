/*
 * Copyright (c) 2010 Mysema Ltd.
 * All rights reserved.
 *
 */
package com.mysema.query.lucene;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.NumericUtils;

import com.mysema.query.QueryMetadata;
import com.mysema.query.types.Constant;
import com.mysema.query.types.Expression;
import com.mysema.query.types.Operation;
import com.mysema.query.types.Operator;
import com.mysema.query.types.Ops;
import com.mysema.query.types.OrderSpecifier;
import com.mysema.query.types.ParamExpression;
import com.mysema.query.types.ParamNotSetException;
import com.mysema.query.types.Path;
import com.mysema.query.types.PathType;
/**
 * Serializes Querydsl queries to Lucene queries.
 *
 * @author vema
 *
 */
public class LuceneSerializer {
    private static final Map<Class<?>, Integer> sortFields = new HashMap<Class<?>, Integer>();

    static {
        sortFields.put(Integer.class, SortField.INT);
        sortFields.put(Float.class, SortField.FLOAT);
        sortFields.put(Long.class, SortField.LONG);
        sortFields.put(Double.class, SortField.DOUBLE);
        sortFields.put(Short.class, SortField.SHORT);
        sortFields.put(Byte.class, SortField.BYTE);
        sortFields.put(BigDecimal.class, SortField.DOUBLE);
        sortFields.put(BigInteger.class, SortField.LONG);
    }

    public static final LuceneSerializer DEFAULT = new LuceneSerializer(false, true);

    private final boolean lowerCase;

    private final boolean splitTerms;
    
    private final Locale sortLocale;

    public LuceneSerializer(boolean lowerCase, boolean splitTerms) {
        this.lowerCase = lowerCase;
        this.splitTerms = splitTerms;
        sortLocale = Locale.getDefault();
    }

    public LuceneSerializer(boolean lowerCase, boolean splitTerms, Locale sortLocale) {
        this.lowerCase = lowerCase;
        this.splitTerms = splitTerms;
        this.sortLocale = sortLocale;
    }
    
    private Query toQuery(Operation<?> operation, QueryMetadata metadata) {
        Operator<?> op = operation.getOperator();
        if (op == Ops.OR) {
            return toTwoHandSidedQuery(operation, Occur.SHOULD, metadata);
        } else if (op == Ops.AND) {
            return toTwoHandSidedQuery(operation, Occur.MUST, metadata);
        } else if (op == Ops.NOT) {
            BooleanQuery bq = new BooleanQuery();
            bq.add(new BooleanClause(toQuery(operation.getArg(0), metadata), Occur.MUST_NOT));
            bq.add(new BooleanClause(new MatchAllDocsQuery(), Occur.MUST));
            return bq;
        } else if (op == Ops.LIKE) {
            return like(operation, metadata);
        } else if (op == Ops.EQ_OBJECT || op == Ops.EQ_PRIMITIVE) {
            return eq(operation, metadata, false);
        } else if (op == Ops.EQ_IGNORE_CASE) {
            throw new IgnoreCaseUnsupportedException();
            // return eq(operation, metadata, true);
        } else if (op == Ops.NE_OBJECT || op == Ops.NE_PRIMITIVE) {
            return ne(operation, metadata, false);
        } else if (op == Ops.STARTS_WITH) {
            return startsWith(metadata, operation, false);
        } else if (op == Ops.STARTS_WITH_IC) {
            throw new IgnoreCaseUnsupportedException();
            // return startsWith(metadata, operation, true);
        } else if (op == Ops.ENDS_WITH) {
            return endsWith(operation, metadata, false);
        } else if (op == Ops.ENDS_WITH_IC) {
            throw new IgnoreCaseUnsupportedException();
            // return endsWith(operation, metadata, true);
        } else if (op == Ops.STRING_CONTAINS) {
            return stringContains(operation, metadata, false);
        } else if (op == Ops.STRING_CONTAINS_IC) {
            throw new IgnoreCaseUnsupportedException();
            // return stringContains(operation, metadata, true);
        } else if (op == Ops.BETWEEN) {
            return between(operation, metadata);
        } else if (op == Ops.IN) {
            return in(operation, metadata, false);
        } else if (op == Ops.LT || op == Ops.BEFORE) {
            return lt(operation, metadata);
        } else if (op == Ops.GT || op == Ops.AFTER) {
            return gt(operation, metadata);
        } else if (op == Ops.LOE || op == Ops.BOE) {
            return le(operation, metadata);
        } else if (op == Ops.GOE || op == Ops.AOE) {
            return ge(operation, metadata);
        } else if (op == Ops.DELEGATE){
            return toQuery(operation.getArg(0), metadata);
        }
        throw new UnsupportedOperationException("Illegal operation " + operation);
    }

    private Query toTwoHandSidedQuery(Operation<?> operation, Occur occur, QueryMetadata metadata) {
        Query lhs = toQuery(operation.getArg(0), metadata);
        Query rhs = toQuery(operation.getArg(1), metadata);
        BooleanQuery bq = new BooleanQuery();
        bq.add(createBooleanClause(lhs, occur));
        bq.add(createBooleanClause(rhs, occur));
        return bq;
    }

    /**
     * If the query is a BooleanQuery and it contains a single Occur.MUST_NOT
     * clause it will be returned as is. Otherwise it will be wrapped in a
     * BooleanClause with the given Occur.
     */
    private BooleanClause createBooleanClause(Query query, Occur occur) {
        if (query instanceof BooleanQuery) {
            BooleanClause[] clauses = ((BooleanQuery) query).getClauses();
            if (clauses.length == 1 && clauses[0].getOccur().equals(Occur.MUST_NOT)) {
                return clauses[0];
            }
        }
        return new BooleanClause(query, occur);
    }

    protected Query like(Operation<?> operation, QueryMetadata metadata) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        String field = toField(path);
        String[] terms = convert(path, operation.getArg(1));
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (String s : terms) {
                bq.add(new WildcardQuery(new Term(field, "*" + s + "*")), Occur.MUST);
            }
            return bq;
        }
        return new WildcardQuery(new Term(field, terms[0]));
    }

    @SuppressWarnings("unchecked")
    protected Query eq(Operation<?> operation, QueryMetadata metadata, boolean ignoreCase) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        String field = toField(path);

        if (Number.class.isAssignableFrom(operation.getArg(1).getType())) {
            return new TermQuery(new Term(field, convertNumber(((Constant<Number>) operation
                    .getArg(1)).getConstant())));
        }

        return eq(field, convert(path, operation.getArg(1), metadata), ignoreCase);
    }


    private String convertNumber(Number number) {
        if (Integer.class.isInstance(number)) {
            return NumericUtils.intToPrefixCoded(number.intValue());
        } else if (Double.class.isInstance(number)) {
            return NumericUtils.doubleToPrefixCoded(number.doubleValue());
        } else if (Long.class.isInstance(number)) {
            return NumericUtils.longToPrefixCoded(number.longValue());
        } else if (Float.class.isInstance(number)) {
            return NumericUtils.floatToPrefixCoded(number.floatValue());
        } else if (Byte.class.isInstance(number)) {
            return NumericUtils.intToPrefixCoded(number.intValue());
        } else if (Short.class.isInstance(number)) {
            return NumericUtils.intToPrefixCoded(number.intValue());
        } else if (BigDecimal.class.isInstance(number)) {
            return NumericUtils.doubleToPrefixCoded(number.doubleValue());
        } else if (BigInteger.class.isInstance(number)) {
            return NumericUtils.longToPrefixCoded(number.longValue());
        } else {
            throw new IllegalArgumentException("Unsupported numeric type "
                    + number.getClass().getName());
        }
    }

    protected Query eq(String field, String[] terms, boolean ignoreCase) {
        if (terms.length > 1) {
            PhraseQuery pq = new PhraseQuery();
            for (String s : terms) {
                pq.add(new Term(field, s));
            }
            return pq;
        }
        return new TermQuery(new Term(field, terms[0]));
    }

    protected Query in(Operation<?> operation, QueryMetadata metadata, boolean ignoreCase) {
        Path<?> path = getPath(operation.getArg(0));
        String field = toField(path);
        Collection<?> values = (Collection<?>) ((Constant<?>) operation.getArg(1)).getConstant();
        BooleanQuery bq = new BooleanQuery();
        for (Object value : values) {
            String[] str = convert(path, value);
            bq.add(eq(field, str, ignoreCase), Occur.SHOULD);
        }
        return bq;
    }

    protected Query ne(Operation<?> operation, QueryMetadata metadata, boolean ignoreCase) {
        BooleanQuery bq = new BooleanQuery();
        bq.add(new BooleanClause(eq(operation, metadata, ignoreCase), Occur.MUST_NOT));
        bq.add(new BooleanClause(new MatchAllDocsQuery(), Occur.MUST));
        return bq;
    }

    protected Query startsWith(QueryMetadata metadata, Operation<?> operation, boolean ignoreCase) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        String field = toField(path);
        String[] terms = convertEscaped(path, operation.getArg(1), metadata);
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (int i = 0; i < terms.length; ++i) {
                String s = i == 0 ? terms[i] + "*" : "*" + terms[i] + "*";
                bq.add(new WildcardQuery(new Term(field, s)), Occur.MUST);
            }
            return bq;
        }
        return new PrefixQuery(new Term(field, terms[0]));
    }



    protected Query stringContains(Operation<?> operation, QueryMetadata metadata, boolean ignoreCase) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        String field = toField(path);
        String[] terms = convertEscaped(path, operation.getArg(1), metadata);
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (String s : terms) {
                bq.add(new WildcardQuery(new Term(field, "*" + s + "*")), Occur.MUST);
            }
            return bq;
        }
        return new WildcardQuery(new Term(field, "*" + terms[0] + "*"));
    }

    protected Query endsWith(Operation<?> operation, QueryMetadata metadata, boolean ignoreCase) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        String field = toField(path);
        String[] terms = convertEscaped(path, operation.getArg(1), metadata);
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (int i = 0; i < terms.length; ++i) {
                String s = i == terms.length - 1 ? "*" + terms[i] : "*" + terms[i] + "*";
                bq.add(new WildcardQuery(new Term(field, s)), Occur.MUST);
            }
            return bq;
        }
        return new WildcardQuery(new Term(field, "*" + terms[0]));
    }

    protected Query between(Operation<?> operation, QueryMetadata metadata) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        // TODO Phrase not properly supported
        return range(
                path,
                toField(path),
                operation.getArg(1),
                operation.getArg(2),
                true,
                true,
                metadata);
    }

    protected Query lt(Operation<?> operation, QueryMetadata metadata) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        return range(
                path,
                toField(path),
                null,
                operation.getArg(1),
                false,
                false,
                metadata);
    }

    protected Query gt(Operation<?> operation, QueryMetadata metadata) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        return range(
                path,
                toField(path),
                operation.getArg(1),
                null,
                false,
                false,
                metadata);
    }

    protected Query le(Operation<?> operation, QueryMetadata metadata) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        return range(
                path,
                toField(path),
                null,
                operation.getArg(1),
                true,
                true,
                metadata);
    }

    protected Query ge(Operation<?> operation, QueryMetadata metadata) {
        verifyArguments(operation);
        Path<?> path = getPath(operation.getArg(0));
        return range(
                path,
                toField(path),
                operation.getArg(1),
                null,
                true,
                true,
                metadata);
    }

    @SuppressWarnings({"unchecked"})
    protected Query range(Path<?> leftHandSide, String field, @Nullable Expression<?> min, @Nullable Expression<?> max, boolean minInc, boolean maxInc, QueryMetadata metadata) {
        if (min != null && Number.class.isAssignableFrom(min.getType()) || max != null
                && Number.class.isAssignableFrom(max.getType())) {
            Class<? extends Number> numType = (Class) (min != null ? min.getType() : max.getType());
            return numericRange((Class) numType, field, (Number) (min == null ? null
                    : ((Constant) min).getConstant()), (Number) (max == null ? null
                    : ((Constant) max).getConstant()), minInc, maxInc);
        }
        return stringRange(leftHandSide, field, min, max, minInc, maxInc, metadata);
    }

    protected <N extends Number> NumericRangeQuery<?> numericRange(Class<N> clazz, String field,
            @Nullable N min, @Nullable N max, boolean minInc, boolean maxInc) {
        if (clazz.equals(Integer.class)) {
            return NumericRangeQuery.newIntRange(field, (Integer) min, (Integer) max, minInc, maxInc);
        } else if (clazz.equals(Double.class)) {
            return NumericRangeQuery.newDoubleRange(field, (Double) min, (Double) max, minInc, minInc);
        } else if (clazz.equals(Float.class)) {
            return NumericRangeQuery.newFloatRange(field, (Float) min, (Float) max, minInc, minInc);
        } else if (clazz.equals(Long.class)) {
            return NumericRangeQuery.newLongRange(field, (Long) min, (Long) max, minInc, minInc);
        } else if (clazz.equals(Byte.class) || clazz.equals(Short.class)) {
            return NumericRangeQuery.newIntRange(field, min != null ? min.intValue() : null,
                    max != null ? max.intValue() : null, minInc, maxInc);
        } else {
            throw new IllegalArgumentException("Unsupported numeric type " + clazz.getName());
        }
    }

    protected Query stringRange(
            Path<?> leftHandSide,
            String field,
            @Nullable Expression<?> min,
            @Nullable Expression<?> max,
            boolean minInc,
            boolean maxInc,
            QueryMetadata metadata) {

        if (min == null) {
            return new TermRangeQuery(
                    field, null, convert(leftHandSide, max, metadata)[0], minInc, maxInc);
        } else if (max == null) {
            return new TermRangeQuery(
                    field, convert(leftHandSide, min, metadata)[0],
                    null, minInc, maxInc);
        } else {
            return new TermRangeQuery(
                    field, convert(leftHandSide, min, metadata)[0],
                    convert(leftHandSide, max, metadata)[0],
                    minInc, maxInc);
        }
    }

    private Path<?> getPath(Expression<?> leftHandSide){
        if (leftHandSide instanceof Path<?>) {
            return (Path<?>)leftHandSide;
        } else if (leftHandSide instanceof Operation<?>) {
            Operation<?> operation = (Operation<?>) leftHandSide;
            if (operation.getOperator() == Ops.LOWER || operation.getOperator() == Ops.UPPER) {
                return (Path<?>)operation.getArg(0);
            }
        }
        throw new IllegalArgumentException("Unable to transform " + leftHandSide + " to path");
    }

    /**
     * template method, override to customize
     *
     * @param path
     * @return
     */
    protected String toField(Path<?> path) {
        String rv = path.getMetadata().getExpression().toString();
        if (path.getMetadata().getParent() != null){
            Path<?> parent = path.getMetadata().getParent();
            if (parent.getMetadata().getPathType() != PathType.VARIABLE){
                rv = toField(parent) + "." + rv;
            }
        }
        return rv;
    }

    private void verifyArguments(Operation<?> operation) {
        List<Expression<?>> arguments = operation.getArgs();
        for (int i = 1; i < arguments.size(); ++i) {
            if (!(arguments.get(i) instanceof Constant<?>)
                    && !(arguments.get(i) instanceof ParamExpression<?>)
                    && !(arguments.get(i) instanceof PhraseElement)
                    && !(arguments.get(i) instanceof TermElement)) {
                throw new IllegalArgumentException("operand was of unsupported type "
                        + arguments.get(i).getClass().getName());
            }
        }
    }

    /**
     * template method
     *
     * @param leftHandSide
     * @param rightHandSide
     * @return
     */
    protected String[] convert(Path<?> leftHandSide, Expression<?> rightHandSide, QueryMetadata metadata) {
        if (rightHandSide instanceof PhraseElement) {
            return StringUtils.split(rightHandSide.toString());
        } else if (rightHandSide instanceof TermElement) {
            return new String[] { rightHandSide.toString() };
        } else if (rightHandSide instanceof ParamExpression<?>){
            Object value = metadata.getParams().get(rightHandSide);
            if (value == null){
                throw new ParamNotSetException((ParamExpression<?>) rightHandSide);
            }
            return convert(leftHandSide, value);

        } else if (rightHandSide instanceof Constant<?>){
            return convert(leftHandSide, ((Constant<?>)rightHandSide).getConstant());
        } else {
            throw new IllegalArgumentException(rightHandSide.toString());
        }
    }

    /**
     * template method
     *
     * @param leftHandSide
     * @param rightHandSide
     * @return
     */
    protected String[] convert(Path<?> leftHandSide, Object rightHandSide) {
        String str = rightHandSide.toString();
        if (lowerCase){
            str = str.toLowerCase();
        }
        if (splitTerms) {
            if (str.equals("")) {
                return new String[] { str };
            } else {
                return StringUtils.split(str);
            }
        } else {
            return new String[] { str };
        }
    }

    private String[] convertEscaped(Path<?> leftHandSide, Expression<?> rightHandSide, QueryMetadata metadata) {
        String[] str = convert(leftHandSide, rightHandSide, metadata);
        for (int i = 0; i < str.length; i++){
            str[i] = QueryParser.escape(str[i]);
        }
        return str;
    }

    public Query toQuery(Expression<?> expr, QueryMetadata metadata) {
        if (expr instanceof Operation<?>) {
            return toQuery((Operation<?>) expr, metadata);
        } else if (expr instanceof QueryElement) {
            return ((QueryElement) expr).getQuery();
        } else{
            throw new IllegalArgumentException("expr was of unsupported type " + expr.getClass().getName());
        }
    }

    public Sort toSort(List<OrderSpecifier<?>> orderBys) {
        List<SortField> sorts = new ArrayList<SortField>(orderBys.size());
        for (OrderSpecifier<?> order : orderBys) {
            if (!(order.getTarget() instanceof Path<?>)) {
                throw new IllegalArgumentException("argument was not of type Path.");
            }
            Class<?> type = order.getTarget().getType();
            boolean reverse = !order.isAscending();
            Path<?> path = getPath(order.getTarget());
            if (Number.class.isAssignableFrom(type)) {
                sorts.add(new SortField(toField(path), sortFields.get(type), reverse));
            } else {
                sorts.add(new SortField(toField(path), sortLocale, reverse));
            }
        }
        Sort sort = new Sort();
        sort.setSort(sorts.toArray(new SortField[sorts.size()]));
        return sort;
    }
}
