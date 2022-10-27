/* eslint-disable react/prop-types */
import React, { useState } from "react";
import { connect } from "react-redux";
import { dissoc } from "icepick";
import { t } from "ttag";

import * as Urls from "metabase/lib/urls";
import withToast from "metabase/hoc/Toast";
import { entityTypeForObject } from "metabase/lib/schema";

import Link from "metabase/core/components/Link";
import ModalContent from "metabase/components/ModalContent";

import Dashboards from "metabase/entities/dashboards";
import Collections from "metabase/entities/collections";
import EntityCopyModal from "metabase/entities/containers/EntityCopyModal";

function mapStateToProps(state, props) {
  return {
    initialCollectionId: Collections.selectors.getInitialCollectionId(state, {
      ...props,
      collectionId: props.entityObject.collection_id,
    }),
  };
}

const getTitle = (entityObject, isShallowCopy) => {
  if (entityObject.model !== "dashboard") {
    return "";
  } else if (isShallowCopy) {
    return t`Duplicate "${entityObject.name}"`;
  } else {
    return t`Duplicate "${entityObject.name}" and its questions`;
  }
};

function CollectionCopyEntityModal({
  entityObject,
  initialCollectionId,
  onClose,
  onSaved,
  triggerToast,
}) {
  const [isShallowCopy, setIsShallowCopy] = useState(true);
  const [newEntityObject, setNewEntityObject] = useState({});
  const [hasUncopiedQuestions, setHasUncopiedQuestions] = useState(false);
  const title = getTitle(entityObject, isShallowCopy);

  const handleValuesChange = ({ is_shallow_copy }) => {
    setIsShallowCopy(is_shallow_copy);
  };

  const afterSaved = () => {
    const newEntityUrl = Urls.modelToUrl({
      model: entityObject.model,
      model_object: newEntityObject,
    });

    triggerToast(
      <div className="flex align-center">
        {t`Duplicated ${entityObject.model}`}
        <Link className="link text-bold ml1" to={newEntityUrl}>
          {t`See it`}
        </Link>
      </div>,
      { icon: entityObject.model },
    );

    onSaved(newEntityObject);
  };

  const handleSaved = savedEntityObject => {
    setNewEntityObject(savedEntityObject);
    if (savedEntityObject.uncopied.length > 0) {
      setHasUncopiedQuestions(true);
    } else {
      afterSaved();
    }
  };

  if (hasUncopiedQuestions) {
    return (
      <ModalContent title="Title" onClose={afterSaved}>
        <div>Close this</div>
      </ModalContent>
    );
  }

  return (
    <EntityCopyModal
      overwriteOnInitialValuesChange
      entityType={entityTypeForObject(entityObject)}
      entityObject={{
        ...entityObject,
        collection_id: initialCollectionId,
      }}
      form={Dashboards.forms.duplicate}
      title={title}
      copy={async values => {
        return entityObject.copy(dissoc(values, "id"));
      }}
      onClose={onClose}
      onSaved={handleSaved}
      onValuesChange={handleValuesChange}
    />
  );
}

export default withToast(connect(mapStateToProps)(CollectionCopyEntityModal));
