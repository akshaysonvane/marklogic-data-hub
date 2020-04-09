import React, { useState, useEffect, useContext } from 'react';
import { Slider, InputNumber, Tooltip } from 'antd';
import { SearchContext } from '../../util/search-context';
import { UserContext } from '../../util/user-context';
import styles from './numeric-facet.module.scss';
import { rangeFacet } from '../../api/facets'

interface Props {
  name: any;
  step: number;
  constraint: string;
  datatype: any;
  referenceType: string;
  entityTypeId: any;
  propertyPath: any;
  onChange: (datatype: any, facetName: any, value: any[]) => void;
};

const NumericFacet: React.FC<Props> = (props) => {
  const {
    searchOptions,
  } = useContext(SearchContext);
  const { handleError, resetSessionTime } = useContext(UserContext);

  const [range, setRange] = useState<number[]>([]);
  const [rangeLimit, setRangeLimit] = useState<number[]>([]);
  let numbers = ['int', 'integer', 'short', 'long', 'decimal', 'double', 'float'];

  const getFacetRange = async () => {
    try {
      const response = await rangeFacet(props)

      if (response.data) {
        let range = [...[response.data.min, response.data.max].map(Number)]
        setRangeLimit(range)

        if (Object.entries(searchOptions.searchFacets).length !== 0 && searchOptions.searchFacets.hasOwnProperty(props.constraint)) {
          for (let facet in searchOptions.searchFacets) {
            if (facet === props.constraint) {
              let valueType = '';
              if (numbers.includes(searchOptions.searchFacets[facet].dataType)) {
                valueType = 'rangeValues';
              }
              if (searchOptions.searchFacets[facet][valueType]) {
                const rangeArray = Object.values(searchOptions.searchFacets[facet][valueType]).map(Number)
                if (rangeArray && rangeArray.length > 0) {
                  setRange(rangeArray)
                }
              }
            }
          }
        } else {
          setRange(range)
        }
      }
    } catch (error) {
      handleError(error)
    } finally {
      resetSessionTime()
    }

  }

  const onChange = (e) => {
    setRange(e);
    props.onChange(props.datatype, props.name, e)
  }

  const onChangeMinInput = (e) => {
    if (e && typeof e === 'number') {
      let modifiedRange = [...range];
      modifiedRange[0] = e;
      setRange(modifiedRange);
      props.onChange(props.datatype, props.name, modifiedRange)
    }
  }

  const onChangeMaxInput = (e) => {
    if (e && typeof e === 'number') {
      let modifiedRange = [...range];
      modifiedRange[1] = e;
      setRange(modifiedRange);
      props.onChange(props.datatype, props.name, modifiedRange)
    }
  }

  useEffect(() => {
    getFacetRange();
  }, []);

  useEffect(() => {
    !Object.keys(searchOptions.searchFacets).includes(props.name) && setRange(rangeLimit)

    if (Object.entries(searchOptions.searchFacets).length !== 0 && searchOptions.searchFacets.hasOwnProperty(props.constraint)) {
      for (let facet in searchOptions.searchFacets) {
        if (facet === props.constraint) {
          let valueType = '';
          if (numbers.includes(searchOptions.searchFacets[facet].dataType)) {
            valueType = 'rangeValues';
          }
          if (searchOptions.searchFacets[facet][valueType]) {
            const rangeArray = Object.values(searchOptions.searchFacets[facet][valueType]).map(Number)
            if (JSON.stringify(range) === JSON.stringify(rangeArray)) {
              if (rangeLimit[0] === rangeArray[0] && rangeLimit[1] === rangeArray[1]) {
                delete searchOptions.searchFacets[facet]
              }
            }
          }
        }
      }
    }
  }, [searchOptions]);

  const formatTitle = () => {
    let objects = props.name.split('.');
    if (objects.length > 2) {
      let first = objects[0];
      let last = objects.slice(-1);
      return first + '. ... .' + last;
    }
    return props.name;
  }

  return (
    <div className={styles.facetName} >
      <p className={styles.name}>{<Tooltip title={props.name}>{formatTitle()}</Tooltip>}</p>
      <div className={styles.numericFacet} data-testid='numeric-slider'>
        <Slider className={styles.slider} range={true} value={[range[0], range[1]]} min={rangeLimit[0]} max={rangeLimit[1]} step={props.step} onChange={(e) => onChange(e)} />
        <InputNumber className={styles.inputNumber} value={range[0]} min={rangeLimit[0]} max={rangeLimit[1]} step={props.step} onChange={onChangeMinInput} />
        <InputNumber className={styles.inputNumber} value={range[1]} min={rangeLimit[0]} max={rangeLimit[1]} step={props.step} onChange={onChangeMaxInput} />
      </div>
    </div>
  )
}

export default NumericFacet;