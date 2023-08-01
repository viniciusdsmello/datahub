import * as QueryString from 'query-string';
import { Maybe } from 'graphql/jsutils/Maybe';

import { Entity, EntityType, MatchedField, EntityRelationshipsResult, DataProduct } from '../../../types.generated';
import { capitalizeFirstLetterOnly } from '../../shared/textUtil';
import { FIELDS_TO_HIGHLIGHT } from '../dataset/search/highlights';
import { GenericEntityProperties } from './types';

export function dictToQueryStringParams(params: Record<string, string | boolean>) {
    return Object.keys(params)
        .map((key) => `${key}=${params[key]}`)
        .join('&');
}

export function urlEncodeUrn(urn: string) {
    return (
        urn &&
        urn
            // Hack - React Router v5 does not like pre-url-encoded paths. Since URNs can contain free form IDs, there's nothing preventing them from having percentages.
            // If we use double encoded paths, React ends up decoding them fully, which breaks our ability to read urns properly.
            .replace(/%/g, '{{encoded_percent}}')
            .replace(/\//g, '%2F')
            .replace(/\?/g, '%3F')
            .replace(/#/g, '%23')
            .replace(/\[/g, '%5B')
            .replace(/\]/g, '%5D')
    );
}

export function decodeUrn(encodedUrn: string) {
    // Hack-This is not ideal because it means that if you had the percent
    // sequence in your urn things may not work as expected.
    return decodeURIComponent(encodedUrn).replace(/{{encoded_percent}}/g, '%');
}

export function getNumberWithOrdinal(n) {
    const suffixes = ['th', 'st', 'nd', 'rd'];
    const v = n % 100;
    return n + (suffixes[(v - 20) % 10] || suffixes[v] || suffixes[0]);
}

export const encodeComma = (str: string) => {
    return str.replace(/,/g, '%2C');
};

export const decodeComma = (str: string) => {
    return str.replace(/%2C/g, ',');
};

export function notEmpty<TValue>(value: TValue | null | undefined): value is TValue {
    return value !== null && value !== undefined;
}

export const truncate = (length: number, input?: string | null) => {
    if (!input) return '';
    if (input.length > length) {
        return `${input.substring(0, length)}...`;
    }
    return input;
};

export const singularizeCollectionName = (collectionName: string): string => {
    if (!collectionName) {
        return collectionName;
    }

    const lastChar = collectionName[collectionName.length - 1];
    if (lastChar === 's') {
        return collectionName.slice(0, -1);
    }

    return collectionName;
};

export function getPlatformName(entityData: GenericEntityProperties | null) {
    return entityData?.platform?.properties?.displayName || capitalizeFirstLetterOnly(entityData?.platform?.name);
}

export const EDITED_DESCRIPTIONS_CACHE_NAME = 'editedDescriptions';

export const FORBIDDEN_URN_CHARS_REGEX = /.*[(),\\].*/;

/**
 * Utility function for checking whether a list is a subset of another.
 */
export const isListSubset = (l1, l2): boolean => {
    return l1.every((result) => l2.indexOf(result) >= 0);
};

function normalize(value: string) {
    return value.trim().toLowerCase();
}

function fromQueryGetBestMatch(selectedMatchedFields: MatchedField[], rawQuery: string) {
    const query = normalize(rawQuery);
    // first lets see if there's an exact match between a field value and the query
    const exactMatch = selectedMatchedFields.find((field) => normalize(field.value) === query);
    if (exactMatch) {
        return exactMatch;
    }

    // if no exact match exists, we'll see if the entire query is contained in any of the values
    const containedMatch = selectedMatchedFields.find((field) => normalize(field.value).includes(query));
    if (containedMatch) {
        return containedMatch;
    }

    // otherwise, just return whichever is first
    return selectedMatchedFields[0];
}

export const getMatchPrioritizingPrimary = (
    matchedFields: MatchedField[],
    primaryField: string,
): MatchedField | undefined => {
    const { location } = window;
    const params = QueryString.parse(location.search, { arrayFormat: 'comma' });
    const query: string = decodeURIComponent(params.query ? (params.query as string) : '');

    const primaryMatches = matchedFields.filter((field) => field.name === primaryField);
    if (primaryMatches.length > 0) {
        return fromQueryGetBestMatch(primaryMatches, query);
    }

    const matchesThatShouldBeShownOnFE = matchedFields.filter((field) => FIELDS_TO_HIGHLIGHT.has(field.name));

    return fromQueryGetBestMatch(matchesThatShouldBeShownOnFE, query);
};

function getGraphqlErrorCode(e) {
    if (e.graphQLErrors && e.graphQLErrors.length) {
        const firstError = e.graphQLErrors[0];
        const { extensions } = firstError;
        const errorCode = extensions && (extensions.code as number);
        return errorCode;
    }
    return undefined;
}

export const handleBatchError = (urns, e, defaultMessage) => {
    if (urns.length > 1 && getGraphqlErrorCode(e) === 403) {
        return {
            content:
                'Your bulk edit selection included entities that you are unauthorized to update. The bulk edit being performed will not be saved.',
            duration: 3,
        };
    }
    return defaultMessage;
};

// put all of the fineGrainedLineages for a given entity and its siblings into one array so we have all of it in one place
export function getFineGrainedLineageWithSiblings(
    entityData: GenericEntityProperties | null,
    getGenericEntityProperties: (type: EntityType, data: Entity) => GenericEntityProperties | null,
) {
    const fineGrainedLineages = [
        ...(entityData?.fineGrainedLineages || entityData?.inputOutput?.fineGrainedLineages || []),
    ];
    entityData?.siblings?.siblings?.forEach((sibling) => {
        if (sibling) {
            const genericSiblingProps = getGenericEntityProperties(sibling.type, sibling);
            if (genericSiblingProps && genericSiblingProps.fineGrainedLineages) {
                fineGrainedLineages.push(...genericSiblingProps.fineGrainedLineages);
            }
        }
    });
    return fineGrainedLineages;
}
export function getDataProduct(dataProductResult: Maybe<EntityRelationshipsResult> | undefined) {
    if (dataProductResult?.relationships && dataProductResult.relationships.length > 0) {
        return dataProductResult.relationships[0].entity as DataProduct;
    }
    return null;
}
