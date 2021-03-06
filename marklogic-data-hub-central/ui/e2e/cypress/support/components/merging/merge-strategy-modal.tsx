class MergeStrategyModal {

  setStrategyName(str: string) {
    cy.get("#strategy-name").focus().clear().type(str);
  }

  addSliderOptionsButton() {
    return cy.findByLabelText("add-slider-button");
  }

  cancelButton() {
    return cy.findByLabelText("cancel-merge-strategy");
  }

  saveButton() {
    return cy.findByLabelText("confirm-merge-strategy");
  }

  maxValue(str: string) {
    cy.get("#maxValuesStrategyInput").focus().clear().type(str);
  }
}

const mergeStrategyModal = new MergeStrategyModal();
export default mergeStrategyModal;
